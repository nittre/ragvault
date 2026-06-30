import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { KeyRound, Copy, Check, Pencil, Trash2, Plus, ToggleLeft, ToggleRight } from 'lucide-react'
import {
  listSiteKeys,
  createSiteKey,
  updateSiteKey,
  deleteSiteKey,
  activateSiteKey,
  deactivateSiteKey,
} from '../../api/admin/sitekeys'
import type { SiteKey, CreateSiteKeyBody, UpdateSiteKeyBody } from '../../api/admin/sitekeys'

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message
  return '알 수 없는 오류가 발생했습니다.'
}

interface CreateModalState {
  mode: 'create'
}

interface EditModalState {
  mode: 'edit'
  item: SiteKey
}

type ModalState = CreateModalState | EditModalState

interface FormValues {
  label: string
  botName: string
  greeting: string
  brandColor: string
  logoUrl: string
  active: boolean
}

const DEFAULT_FORM: FormValues = {
  label: '',
  botName: '',
  greeting: '',
  brandColor: '#2563eb',
  logoUrl: '',
  active: true,
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // clipboard not available
    }
  }

  return (
    <button
      onClick={handleCopy}
      className="ml-1.5 text-gray-400 hover:text-blue-600 transition-colors shrink-0"
      title="클립보드에 복사"
    >
      {copied ? <Check size={13} className="text-green-500" /> : <Copy size={13} />}
    </button>
  )
}

export default function SiteKeysPage() {
  const qc = useQueryClient()

  const [modal, setModal] = useState<ModalState | null>(null)
  const [form, setForm] = useState<FormValues>(DEFAULT_FORM)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const { data: siteKeys = [], isLoading, error: listError } = useQuery({
    queryKey: ['admin-sitekeys'],
    queryFn: listSiteKeys,
  })

  const createMut = useMutation({
    mutationFn: (body: CreateSiteKeyBody) => createSiteKey(body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-sitekeys'] })
      closeModal()
    },
    onError: (err: unknown) => setErrorMsg(errorMessage(err)),
  })

  const updateMut = useMutation({
    mutationFn: ({ id, body }: { id: number; body: UpdateSiteKeyBody }) => updateSiteKey(id, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-sitekeys'] })
      closeModal()
    },
    onError: (err: unknown) => setErrorMsg(errorMessage(err)),
  })

  const deleteMut = useMutation({
    mutationFn: (id: number) => deleteSiteKey(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-sitekeys'] }),
    onError: (err: unknown) => setErrorMsg(errorMessage(err)),
  })

  const toggleMut = useMutation({
    mutationFn: ({ id, active }: { id: number; active: boolean }) =>
      active ? deactivateSiteKey(id) : activateSiteKey(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-sitekeys'] }),
    onError: (err: unknown) => setErrorMsg(errorMessage(err)),
  })

  function openCreate() {
    setForm(DEFAULT_FORM)
    setErrorMsg(null)
    setModal({ mode: 'create' })
  }

  function openEdit(item: SiteKey) {
    setForm({
      label: item.label,
      botName: item.botName,
      greeting: item.greeting,
      brandColor: item.brandColor || '#2563eb',
      logoUrl: item.logoUrl || '',
      active: item.active,
    })
    setErrorMsg(null)
    setModal({ mode: 'edit', item })
  }

  function closeModal() {
    setModal(null)
    setForm(DEFAULT_FORM)
    setErrorMsg(null)
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setErrorMsg(null)
    if (modal?.mode === 'create') {
      createMut.mutate({
        label: form.label,
        botName: form.botName || undefined,
        greeting: form.greeting || undefined,
        brandColor: form.brandColor || undefined,
        logoUrl: form.logoUrl || undefined,
      })
    } else if (modal?.mode === 'edit') {
      updateMut.mutate({
        id: modal.item.id,
        body: {
          label: form.label,
          active: form.active,
          brandColor: form.brandColor,
          botName: form.botName,
          greeting: form.greeting,
          logoUrl: form.logoUrl || undefined,
        },
      })
    }
  }

  function handleDelete(item: SiteKey) {
    if (window.confirm(`"${item.label}" site-key를 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.`)) {
      deleteMut.mutate(item.id)
    }
  }

  function handleToggle(item: SiteKey) {
    toggleMut.mutate({ id: item.id, active: item.active })
  }

  const isMutating = createMut.isPending || updateMut.isPending

  return (
    <div className="p-6">
      {/* 헤더 */}
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-2">
          <KeyRound size={20} className="text-gray-500" />
          <h1 className="text-xl font-semibold text-gray-900">위젯 설정</h1>
        </div>
        <button
          onClick={openCreate}
          className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-3 py-2 rounded-lg text-sm font-medium transition-colors"
        >
          <Plus size={15} />
          새 site-key 발급
        </button>
      </div>

      {/* 인라인 에러 */}
      {errorMsg && (
        <div className="mb-4 bg-red-50 border border-red-200 text-red-600 text-sm px-4 py-2 rounded-lg flex items-center justify-between">
          <span>오류: {errorMsg}</span>
          <button
            onClick={() => setErrorMsg(null)}
            className="ml-4 text-red-400 hover:text-red-600 text-xs"
          >
            닫기
          </button>
        </div>
      )}

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {listError && <p className="text-sm text-red-500">오류: {String(listError)}</p>}

      {/* 카드 그리드 */}
      {!isLoading && (
        <>
          {siteKeys.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-20 text-gray-400">
              <KeyRound size={40} className="mb-3 opacity-30" />
              <p className="text-sm">발급된 site-key가 없습니다.</p>
              <p className="text-xs mt-1">위의 버튼으로 새 site-key를 발급하세요.</p>
            </div>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-4">
              {siteKeys.map(item => (
                <div
                  key={item.id}
                  className="bg-white border border-gray-200 rounded-xl p-5 flex flex-col gap-3 hover:shadow-sm transition-shadow"
                >
                  {/* 카드 헤더 */}
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex items-center gap-2 min-w-0">
                      {/* 브랜드 색상 스와치 */}
                      <span
                        className="w-4 h-4 rounded-full shrink-0 border border-white shadow-sm"
                        style={{ backgroundColor: item.brandColor || '#2563eb' }}
                      />
                      <span className="font-medium text-gray-900 text-sm truncate">{item.label}</span>
                    </div>
                    <span
                      className={`shrink-0 text-xs font-medium px-2 py-0.5 rounded-full ${
                        item.active
                          ? 'bg-green-50 text-green-700 border border-green-200'
                          : 'bg-gray-100 text-gray-400 border border-gray-200'
                      }`}
                    >
                      {item.active ? '활성' : '비활성'}
                    </span>
                  </div>

                  {/* site-key 값 */}
                  <div className="bg-gray-50 rounded-lg px-3 py-2 flex items-center gap-1">
                    <span
                      className="font-mono text-xs text-gray-600 truncate cursor-pointer hover:text-blue-600 transition-colors flex-1"
                      title="클릭하여 복사"
                      onClick={async () => {
                        try {
                          await navigator.clipboard.writeText(item.siteKey)
                        } catch {
                          // ignore
                        }
                      }}
                    >
                      {item.siteKey}
                    </span>
                    <CopyButton text={item.siteKey} />
                  </div>

                  {/* 메타 */}
                  <div className="text-xs text-gray-400 space-y-0.5">
                    {item.botName && (
                      <div>봇 이름: <span className="text-gray-600">{item.botName}</span></div>
                    )}
                    <div>발급일: {formatDate(item.createdAt)}</div>
                  </div>

                  {/* 카드 하단 액션 */}
                  <div className="flex items-center gap-2 pt-1 border-t border-gray-100 mt-auto">
                    <button
                      onClick={() => openEdit(item)}
                      className="flex items-center gap-1 text-xs text-gray-500 hover:text-blue-600 transition-colors px-2 py-1 rounded hover:bg-blue-50"
                    >
                      <Pencil size={12} />
                      편집
                    </button>
                    <button
                      onClick={() => handleToggle(item)}
                      disabled={toggleMut.isPending}
                      className={`flex items-center gap-1 text-xs transition-colors px-2 py-1 rounded disabled:opacity-50 ${
                        item.active
                          ? 'text-gray-500 hover:text-amber-600 hover:bg-amber-50'
                          : 'text-gray-500 hover:text-green-600 hover:bg-green-50'
                      }`}
                    >
                      {item.active ? (
                        <>
                          <ToggleRight size={12} />
                          비활성화
                        </>
                      ) : (
                        <>
                          <ToggleLeft size={12} />
                          활성화
                        </>
                      )}
                    </button>
                    <button
                      onClick={() => handleDelete(item)}
                      disabled={deleteMut.isPending}
                      className="flex items-center gap-1 text-xs text-gray-400 hover:text-red-600 transition-colors px-2 py-1 rounded hover:bg-red-50 ml-auto disabled:opacity-50"
                    >
                      <Trash2 size={12} />
                      삭제
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </>
      )}

      {/* 생성/편집 모달 */}
      {modal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-xl shadow-xl w-full max-w-md mx-4 flex flex-col max-h-[90vh]">
            {/* 모달 헤더 */}
            <div className="px-6 py-4 border-b border-gray-200 flex items-center justify-between shrink-0">
              <h2 className="text-base font-semibold text-gray-900">
                {modal.mode === 'create' ? '새 site-key 발급' : 'site-key 편집'}
              </h2>
              <button
                onClick={closeModal}
                className="text-gray-400 hover:text-gray-600 transition-colors text-lg leading-none"
              >
                ×
              </button>
            </div>

            {/* 모달 본문 */}
            <form onSubmit={handleSubmit} className="flex flex-col flex-1 overflow-hidden">
              <div className="px-6 py-4 flex flex-col gap-4 flex-1 overflow-y-auto">
                {/* label */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">
                    레이블 <span className="text-red-400">*</span>
                  </label>
                  <input
                    required
                    value={form.label}
                    onChange={e => setForm(f => ({ ...f, label: e.target.value }))}
                    placeholder="예: 홈페이지 위젯"
                    className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-full focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>

                {/* botName */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">봇 이름</label>
                  <input
                    value={form.botName}
                    onChange={e => setForm(f => ({ ...f, botName: e.target.value }))}
                    placeholder="예: 무엇이든 물어보세요 👋"
                    className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-full focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>

                {/* greeting */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">인사말</label>
                  <textarea
                    value={form.greeting}
                    onChange={e => setForm(f => ({ ...f, greeting: e.target.value }))}
                    rows={3}
                    placeholder="예: 안녕하세요! 무엇이든 질문해 주세요."
                    className="border border-gray-300 rounded-lg px-3 py-2 text-sm w-full focus:outline-none focus:ring-2 focus:ring-blue-500 resize-y"
                  />
                </div>

                {/* brandColor */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">브랜드 색상</label>
                  <div className="flex items-center gap-2">
                    <input
                      type="color"
                      value={form.brandColor}
                      onChange={e => setForm(f => ({ ...f, brandColor: e.target.value }))}
                      className="w-9 h-9 rounded cursor-pointer border border-gray-300 p-0.5"
                    />
                    <span className="font-mono text-sm text-gray-500">{form.brandColor}</span>
                  </div>
                </div>

                {/* logoUrl */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">로고 URL</label>
                  <input
                    type="url"
                    value={form.logoUrl}
                    onChange={e => setForm(f => ({ ...f, logoUrl: e.target.value }))}
                    placeholder="https://example.com/logo.png"
                    className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-full focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>

                {/* active (편집 시만) */}
                {modal.mode === 'edit' && (
                  <div className="flex items-center gap-2">
                    <input
                      type="checkbox"
                      id="active-checkbox"
                      checked={form.active}
                      onChange={e => setForm(f => ({ ...f, active: e.target.checked }))}
                      className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                    />
                    <label htmlFor="active-checkbox" className="text-sm text-gray-700 select-none cursor-pointer">
                      활성화
                    </label>
                  </div>
                )}

                {errorMsg && (
                  <p className="text-xs text-red-500">오류: {errorMsg}</p>
                )}
              </div>

              {/* 모달 푸터 */}
              <div className="px-6 py-4 border-t border-gray-200 flex items-center justify-end gap-2 shrink-0">
                <button
                  type="button"
                  onClick={closeModal}
                  className="text-gray-500 hover:text-gray-700 px-4 py-1.5 text-sm transition-colors"
                >
                  취소
                </button>
                <button
                  type="submit"
                  disabled={isMutating}
                  className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-1.5 rounded-lg text-sm font-medium transition-colors"
                >
                  {isMutating ? '저장 중…' : modal.mode === 'create' ? '발급' : '저장'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
