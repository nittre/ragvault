import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { BookOpen, Plus, Pencil, Trash2, Pin, X, Save } from 'lucide-react'
import {
  getKnowledge,
  updateKnowledge,
  type KnowledgeEntry,
  type KnowledgeRole,
} from '../../api/admin/knowledge'

const EMPTY_ENTRY: Omit<KnowledgeEntry, 'id'> = {
  title: '',
  knowledgeRole: 'rule',
  content: '',
  pinned: false,
}

export default function KnowledgePage() {
  const { dsId: dsIdStr } = useParams<{ dsId: string }>()
  const dsId = Number(dsIdStr)
  const qc = useQueryClient()

  const { data, isLoading, error } = useQuery({
    queryKey: ['admin-knowledge', dsId],
    queryFn: () => getKnowledge(dsId),
    enabled: !!dsId,
  })

  const [items, setItems] = useState<KnowledgeEntry[]>([])
  const [modal, setModal] = useState<{ open: boolean; idx: number; draft: KnowledgeEntry } | null>(null)

  useEffect(() => {
    if (data) setItems(data)
  }, [data])

  const saveMut = useMutation({
    mutationFn: (next: KnowledgeEntry[]) =>
      updateKnowledge(dsId, next.filter(i => i.content.trim() !== '')),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-knowledge', dsId] }),
  })

  // ── modal helpers ─────────────────────────────────────────────────────────
  const openAdd = () =>
    setModal({ open: true, idx: -1, draft: { ...EMPTY_ENTRY } })

  const openEdit = (idx: number) =>
    setModal({ open: true, idx, draft: { ...items[idx] } })

  const closeModal = () => setModal(null)

  const saveModal = () => {
    if (!modal) return
    const next = modal.idx === -1
      ? [...items, modal.draft]
      : items.map((it, i) => (i === modal.idx ? modal.draft : it))
    setItems(next)
    saveMut.mutate(next)
    setModal(null)
  }

  const deleteItem = (idx: number) => {
    const next = items.filter((_, i) => i !== idx)
    setItems(next)
    saveMut.mutate(next)
  }

  const patchDraft = (patch: Partial<KnowledgeEntry>) =>
    setModal(m => m ? { ...m, draft: { ...m.draft, ...patch } } : m)

  // ── role badge ────────────────────────────────────────────────────────────
  const RoleBadge = ({ role }: { role: KnowledgeRole }) =>
    role === 'measure' ? (
      <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-purple-100 text-purple-700">
        측정
      </span>
    ) : (
      <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-blue-100 text-blue-700">
        규칙
      </span>
    )

  return (
    <div className="p-6">
      {/* header */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold text-gray-900 flex items-center gap-2">
            <BookOpen size={20} className="text-blue-600" /> 백과사전
          </h1>
          <p className="text-sm text-gray-500 mt-0.5">
            비즈니스 규칙·계산식·도메인 도출 쿼리를 항목 단위로 관리합니다.
          </p>
        </div>
        <button
          onClick={openAdd}
          className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-4 py-2 rounded-lg text-sm font-medium"
        >
          <Plus size={15} /> 항목 추가
        </button>
      </div>

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      {/* table */}
      {!isLoading && (
        <div className="border border-gray-200 rounded-xl overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
              <tr>
                <th className="px-4 py-3 text-left font-medium w-8">#</th>
                <th className="px-4 py-3 text-left font-medium">제목</th>
                <th className="px-4 py-3 text-left font-medium w-24">역할</th>
                <th className="px-4 py-3 text-left font-medium">본문 미리보기</th>
                <th className="px-4 py-3 text-center font-medium w-16">고정</th>
                <th className="px-4 py-3 text-center font-medium w-24">작업</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 bg-white">
              {items.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-4 py-8 text-center text-gray-400 text-sm">
                    등록된 항목이 없습니다. 항목 추가 버튼을 눌러 첫 번째 지식을 추가하세요.
                  </td>
                </tr>
              )}
              {items.map((it, idx) => (
                <tr key={it.id ?? `new-${idx}`} className="hover:bg-gray-50">
                  <td className="px-4 py-3 text-gray-400">{idx + 1}</td>
                  <td className="px-4 py-3 font-medium text-gray-900">
                    {it.title || <span className="text-gray-400 italic">제목 없음</span>}
                  </td>
                  <td className="px-4 py-3">
                    <RoleBadge role={it.knowledgeRole} />
                  </td>
                  <td className="px-4 py-3 text-gray-500 font-mono max-w-xs truncate">
                    {it.content}
                  </td>
                  <td className="px-4 py-3 text-center">
                    {it.pinned ? (
                      <Pin size={14} className="inline fill-amber-400 text-amber-400" />
                    ) : (
                      <span className="text-gray-300">—</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-center">
                    <div className="flex items-center justify-center gap-1">
                      <button
                        onClick={() => openEdit(idx)}
                        className="p-1.5 rounded hover:bg-blue-50 text-gray-400 hover:text-blue-600"
                        title="수정"
                      >
                        <Pencil size={14} />
                      </button>
                      <button
                        onClick={() => deleteItem(idx)}
                        className="p-1.5 rounded hover:bg-red-50 text-gray-400 hover:text-red-500"
                        title="삭제"
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* edit modal */}
      {modal?.open && (
        <div className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4">
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-lg">
            {/* modal header */}
            <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100">
              <h2 className="font-semibold text-gray-900">
                {modal.idx === -1 ? '항목 추가' : '항목 수정'}
              </h2>
              <button onClick={closeModal} className="text-gray-400 hover:text-gray-600">
                <X size={18} />
              </button>
            </div>

            {/* modal body */}
            <div className="px-6 py-4 space-y-4">
              {/* title */}
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1">제목</label>
                <input
                  value={modal.draft.title}
                  onChange={e => patchDraft({ title: e.target.value })}
                  placeholder="어떤 도메인인지 한 줄 설명 (예: 진행중인 부트캠프)"
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
                />
              </div>

              {/* role + pinned row */}
              <div className="flex items-center gap-4">
                <div className="flex-1">
                  <label className="block text-xs font-medium text-gray-600 mb-1">역할</label>
                  <select
                    value={modal.draft.knowledgeRole}
                    onChange={e => patchDraft({ knowledgeRole: e.target.value as KnowledgeRole })}
                    className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm bg-white focus:outline-none focus:ring-2 focus:ring-blue-400"
                  >
                    <option value="rule">규칙 (rule) — 정의·공식·제약</option>
                    <option value="measure">측정 (measure) — 도메인 도출 쿼리</option>
                  </select>
                </div>
                <div className="pt-5">
                  <label className="flex items-center gap-2 cursor-pointer select-none">
                    <input
                      type="checkbox"
                      checked={modal.draft.pinned}
                      onChange={e => patchDraft({ pinned: e.target.checked })}
                      className="w-4 h-4 rounded accent-amber-500"
                    />
                    <span className="text-sm text-gray-700 flex items-center gap-1">
                      <Pin size={13} className={modal.draft.pinned ? 'text-amber-500' : 'text-gray-400'} />
                      고정 (항상 주입)
                    </span>
                  </label>
                </div>
              </div>

              {/* content */}
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1">본문</label>
                <textarea
                  value={modal.draft.content}
                  onChange={e => patchDraft({ content: e.target.value })}
                  rows={modal.draft.knowledgeRole === 'measure' ? 6 : 4}
                  placeholder={
                    modal.draft.knowledgeRole === 'measure'
                      ? "SELECT * FROM bootcamp WHERE status = 'ongoing'\n-- SQL과 자연어 설명을 함께 작성해도 됩니다"
                      : '매출 = COALESCE(amount,0) - COALESCE(cancel_amount,0)'
                  }
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm font-mono resize-y focus:outline-none focus:ring-2 focus:ring-blue-400"
                />
                {modal.draft.knowledgeRole === 'measure' && (
                  <p className="text-xs text-gray-400 mt-1">
                    이 쿼리는 SQL 생성 시 few-shot 예시로 LLM에 전달됩니다.
                  </p>
                )}
              </div>
            </div>

            {/* modal footer */}
            <div className="flex justify-end gap-2 px-6 py-4 border-t border-gray-100">
              <button
                onClick={closeModal}
                className="px-4 py-2 rounded-lg text-sm text-gray-600 hover:bg-gray-100"
              >
                취소
              </button>
              <button
                onClick={saveModal}
                disabled={saveMut.isPending}
                className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-2 rounded-lg text-sm font-medium"
              >
                <Save size={14} />
                {saveMut.isPending ? '저장 중…' : '저장'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
