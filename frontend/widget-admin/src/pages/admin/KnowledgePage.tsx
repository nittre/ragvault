import { useRef, useState, useEffect } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { FilePlus, RefreshCw, Upload } from 'lucide-react'
import {
  listKnowledge,
  getKnowledgeContent,
  createKnowledge,
  updateKnowledge,
  deleteKnowledge,
  reloadKnowledge,
  reloadAllKnowledge,
  uploadKnowledge,
  isTextFile,
  extOf,
} from '../../api/admin/knowledge'
import type { KnowledgeFileInfo } from '../../api/admin/knowledge'

const ACCEPTED_EXTS = '.md,.txt,.docx,.xlsx,.pptx,.pdf'

const EXT_BADGE: Record<string, string> = {
  md:   'bg-gray-100 text-gray-600',
  txt:  'bg-gray-100 text-gray-600',
  docx: 'bg-blue-50 text-blue-700',
  xlsx: 'bg-green-50 text-green-700',
  pptx: 'bg-orange-50 text-orange-700',
  pdf:  'bg-red-50 text-red-700',
}

function formatSize(bytes: number): string {
  if (bytes < 0) return '-'
  if (bytes < 1024) return `${bytes} B`
  return `${(bytes / 1024).toFixed(1)} KB`
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ko-KR', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

function ExtBadge({ name }: { name: string }) {
  const ext = extOf(name)
  const cls = EXT_BADGE[ext] ?? 'bg-gray-100 text-gray-500'
  return (
    <span className={`inline-block text-xs font-mono px-1.5 py-0.5 rounded ${cls}`}>
      .{ext}
    </span>
  )
}

interface ModalState { mode: 'create' | 'edit'; fileId?: string }

export default function KnowledgePage() {
  const qc = useQueryClient()
  const fileInputRef = useRef<HTMLInputElement>(null)

  const [modal, setModal] = useState<ModalState | null>(null)
  const [formName, setFormName] = useState('')
  const [formContent, setFormContent] = useState('')
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [reloadingId, setReloadingId] = useState<string | null>(null)
  const [reloadingAll, setReloadingAll] = useState(false)
  const [uploading, setUploading] = useState(false)

  const { data: docs = [], isLoading, error: listError } = useQuery({
    queryKey: ['admin-knowledge'],
    queryFn: listKnowledge,
  })

  const { isFetching: isContentFetching, data: docContent } = useQuery({
    queryKey: ['admin-knowledge-content', modal?.fileId],
    queryFn: () => getKnowledgeContent(modal!.fileId!),
    enabled: modal?.mode === 'edit' && !!modal.fileId,
  })

  useEffect(() => {
    if (docContent) setFormContent(docContent.content)
  }, [docContent])

  const createMut = useMutation({
    mutationFn: createKnowledge,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['admin-knowledge'] }); closeModal() },
    onError: (err: unknown) => setErrorMsg(errMsg(err)),
  })

  const updateMut = useMutation({
    mutationFn: ({ fileId, content }: { fileId: string; content: string }) =>
      updateKnowledge(fileId, { content }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['admin-knowledge'] }); closeModal() },
    onError: (err: unknown) => setErrorMsg(errMsg(err)),
  })

  const deleteMut = useMutation({
    mutationFn: deleteKnowledge,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-knowledge'] }),
    onError: (err: unknown) => setErrorMsg(errMsg(err)),
  })

  function errMsg(err: unknown): string {
    if (err instanceof Error) return err.message
    return '알 수 없는 오류가 발생했습니다.'
  }

  function openCreate() {
    setFormName(''); setFormContent(''); setErrorMsg(null)
    setModal({ mode: 'create' })
  }

  function openEdit(doc: KnowledgeFileInfo) {
    setFormContent(''); setErrorMsg(null)
    setModal({ mode: 'edit', fileId: doc.name })
  }

  function closeModal() {
    setModal(null); setFormName(''); setFormContent(''); setErrorMsg(null)
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault(); setErrorMsg(null)
    if (modal?.mode === 'create') {
      createMut.mutate({ name: formName, content: formContent })
    } else if (modal?.mode === 'edit' && modal.fileId) {
      updateMut.mutate({ fileId: modal.fileId, content: formContent })
    }
  }

  function handleDelete(doc: KnowledgeFileInfo) {
    if (window.confirm(`"${doc.name}" 파일을 삭제하시겠습니까?`)) {
      deleteMut.mutate(doc.name)
    }
  }

  async function handleReload(doc: KnowledgeFileInfo) {
    setReloadingId(doc.name)
    try { await reloadKnowledge(doc.name) }
    catch (err) { setErrorMsg(errMsg(err)) }
    finally { setReloadingId(null) }
  }

  async function handleReloadAll() {
    setReloadingAll(true)
    try { await reloadAllKnowledge() }
    catch (err) { setErrorMsg(errMsg(err)) }
    finally { setReloadingAll(false) }
  }

  async function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    e.target.value = ''
    setUploading(true); setErrorMsg(null)
    try {
      await uploadKnowledge(file)
      qc.invalidateQueries({ queryKey: ['admin-knowledge'] })
    } catch (err) {
      setErrorMsg(errMsg(err))
    } finally {
      setUploading(false)
    }
  }

  const isMutating = createMut.isPending || updateMut.isPending

  return (
    <div className="p-6">
      <input
        ref={fileInputRef}
        type="file"
        accept={ACCEPTED_EXTS}
        className="hidden"
        onChange={handleFileChange}
      />

      {/* 헤더 */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">지식문서 관리</h1>
          <p className="text-xs text-gray-400 mt-0.5">
            .md · .docx · .xlsx · .pptx · .pdf 지원 — 이미지/표 포함 문서도 벡터화됩니다
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={handleReloadAll}
            disabled={reloadingAll}
            className="flex items-center gap-2 border border-gray-300 hover:bg-gray-50 disabled:opacity-50 text-gray-700 px-3 py-2 rounded-lg text-sm font-medium transition-colors"
          >
            <RefreshCw size={14} className={reloadingAll ? 'animate-spin' : ''} />
            전체 재임베딩
          </button>
          <button
            onClick={() => fileInputRef.current?.click()}
            disabled={uploading}
            className="flex items-center gap-2 border border-blue-300 hover:bg-blue-50 disabled:opacity-50 text-blue-700 px-3 py-2 rounded-lg text-sm font-medium transition-colors"
          >
            <Upload size={14} className={uploading ? 'animate-pulse' : ''} />
            {uploading ? '업로드 중…' : '파일 업로드'}
          </button>
          <button
            onClick={openCreate}
            className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-3 py-2 rounded-lg text-sm font-medium transition-colors"
          >
            <FilePlus size={15} />
            마크다운 추가
          </button>
        </div>
      </div>

      {/* 인라인 에러 */}
      {errorMsg && (
        <div className="mb-4 bg-red-50 border border-red-200 text-red-600 text-sm px-4 py-2 rounded-lg flex items-center justify-between">
          <span>오류: {errorMsg}</span>
          <button onClick={() => setErrorMsg(null)} className="ml-4 text-red-400 hover:text-red-600 text-xs">닫기</button>
        </div>
      )}

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {listError && <p className="text-sm text-red-500">오류: {String(listError)}</p>}

      {!isLoading && (
        <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                {['파일명', '형식', '크기', '수정일', ''].map(h => (
                  <th key={h} className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wide">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {docs.map(doc => (
                <tr key={doc.name} className="hover:bg-gray-50">
                  <td className="px-4 py-3 text-gray-900 font-mono text-xs">{doc.name}</td>
                  <td className="px-4 py-3"><ExtBadge name={doc.name} /></td>
                  <td className="px-4 py-3 text-gray-500 text-xs">{formatSize(doc.sizeBytes)}</td>
                  <td className="px-4 py-3 text-gray-400 text-xs">{formatDate(doc.lastModified)}</td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-3 justify-end">
                      <button
                        onClick={() => handleReload(doc)}
                        disabled={reloadingId === doc.name}
                        className="text-gray-400 hover:text-blue-600 text-xs transition-colors disabled:opacity-50 flex items-center gap-1"
                      >
                        <RefreshCw size={12} className={reloadingId === doc.name ? 'animate-spin' : ''} />
                        재임베딩
                      </button>
                      {isTextFile(doc.name) && (
                        <button
                          onClick={() => openEdit(doc)}
                          className="text-blue-500 hover:text-blue-700 text-xs transition-colors"
                        >
                          편집
                        </button>
                      )}
                      <button
                        onClick={() => handleDelete(doc)}
                        disabled={deleteMut.isPending}
                        className="text-red-400 hover:text-red-600 text-xs transition-colors disabled:opacity-50"
                      >
                        삭제
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {docs.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-8 text-center text-gray-400 text-sm">
                    지식문서가 없습니다. 파일을 업로드하거나 마크다운을 추가하세요.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {/* 마크다운 생성/편집 모달 */}
      {modal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-xl shadow-xl w-full max-w-2xl mx-4 flex flex-col max-h-[90vh]">
            <div className="px-6 py-4 border-b border-gray-200 flex items-center justify-between shrink-0">
              <h2 className="text-base font-semibold text-gray-900">
                {modal.mode === 'create' ? '마크다운 문서 추가' : '마크다운 편집'}
              </h2>
              <button onClick={closeModal} className="text-gray-400 hover:text-gray-600 transition-colors text-lg leading-none">×</button>
            </div>

            <form onSubmit={handleSubmit} className="flex flex-col flex-1 overflow-hidden">
              <div className="px-6 py-4 flex flex-col gap-4 flex-1 overflow-y-auto">
                {modal.mode === 'create' && (
                  <div>
                    <label className="block text-xs font-medium text-gray-600 mb-1">
                      파일명 <span className="text-red-400">*</span>
                    </label>
                    <input
                      required
                      value={formName}
                      onChange={e => setFormName(e.target.value)}
                      placeholder="예: product-guide.md"
                      className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-full focus:outline-none focus:ring-2 focus:ring-blue-500"
                    />
                  </div>
                )}
                {modal.mode === 'edit' && (
                  <div>
                    <label className="block text-xs font-medium text-gray-600 mb-1">파일명</label>
                    <div className="border border-gray-200 rounded-lg px-3 py-1.5 text-sm bg-gray-50 text-gray-500 font-mono">
                      {modal.fileId}
                    </div>
                  </div>
                )}
                <div className="flex flex-col flex-1">
                  <label className="block text-xs font-medium text-gray-600 mb-1">
                    내용 (Markdown) <span className="text-red-400">*</span>
                  </label>
                  {isContentFetching ? (
                    <div className="flex items-center justify-center h-40 text-sm text-gray-400">불러오는 중…</div>
                  ) : (
                    <textarea
                      required
                      value={formContent}
                      onChange={e => setFormContent(e.target.value)}
                      rows={24}
                      placeholder="# 문서 제목&#10;&#10;## 섹션&#10;&#10;내용을 입력하세요."
                      className="border border-gray-300 rounded-lg px-3 py-2 text-sm font-mono resize-y focus:outline-none focus:ring-2 focus:ring-blue-500 w-full leading-relaxed"
                    />
                  )}
                </div>
                {errorMsg && <p className="text-xs text-red-500">오류: {errorMsg}</p>}
              </div>

              <div className="px-6 py-4 border-t border-gray-200 flex items-center justify-end gap-2 shrink-0">
                <button type="button" onClick={closeModal} className="text-gray-500 hover:text-gray-700 px-4 py-1.5 text-sm transition-colors">
                  취소
                </button>
                <button
                  type="submit"
                  disabled={isMutating || isContentFetching}
                  className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-1.5 rounded-lg text-sm font-medium transition-colors"
                >
                  {isMutating ? '저장 중…' : '저장'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
