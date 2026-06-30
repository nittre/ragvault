import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, Trash2, Shield, Pencil, X, Check } from 'lucide-react'
import {
  getMaskingRules,
  createMaskingRule,
  updateMaskingRule,
  deleteMaskingRule,
  bulkCreateMaskingRules,
  type MaskingRule,
  type SuggestedMaskingRule,
} from '../../api/admin/maskingRules'
import PiiSuggestModal from '../../components/admin/PiiSuggestModal'

const LEVEL_LABEL: Record<string, string> = {
  standard: '기본',
  aggressive: '강화',
}
const LEVEL_COLOR: Record<string, string> = {
  standard: 'bg-blue-100 text-blue-700',
  aggressive: 'bg-orange-100 text-orange-700',
}

type EditForm = {
  name: string
  pattern: string
  replacement: string
  level: 'standard' | 'aggressive'
  enabled: boolean
  sortOrder: number
}

const emptyForm = (): EditForm => ({
  name: '', pattern: '', replacement: '', level: 'standard', enabled: true, sortOrder: 100,
})

export default function MaskingRulesPage() {
  const { dsId: dsIdStr } = useParams<{ dsId: string }>()
  const dsId = Number(dsIdStr)
  const qc = useQueryClient()

  const { data: rules = [], isLoading, error } = useQuery({
    queryKey: ['admin-masking-rules', dsId],
    queryFn: () => getMaskingRules(dsId),
    enabled: !!dsId,
  })

  const createMut = useMutation({
    mutationFn: (body: Omit<MaskingRule, 'id' | 'datasourceId' | 'createdAt' | 'updatedAt'>) =>
      createMaskingRule(dsId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-masking-rules', dsId] })
      setShowForm(false)
      setCreateForm(emptyForm())
    },
  })

  const updateMut = useMutation({
    mutationFn: ({ id, body }: { id: number; body: Partial<Omit<MaskingRule, 'id' | 'datasourceId' | 'createdAt' | 'updatedAt'>> }) =>
      updateMaskingRule(dsId, id, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-masking-rules', dsId] })
      setEditingId(null)
    },
  })

  const deleteMut = useMutation({
    mutationFn: (id: number) => deleteMaskingRule(dsId, id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-masking-rules', dsId] }),
  })

  const [showForm, setShowForm] = useState(false)
  const [showSuggest, setShowSuggest] = useState(false)
  const [createForm, setCreateForm] = useState<EditForm>(emptyForm())
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editForm, setEditForm] = useState<EditForm>(emptyForm())

  const startEdit = (r: MaskingRule) => {
    setEditingId(r.id)
    setEditForm({ name: r.name, pattern: r.pattern, replacement: r.replacement, level: r.level, enabled: r.enabled, sortOrder: r.sortOrder })
  }

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault()
    createMut.mutate(createForm)
  }

  const handleUpdate = (e: React.FormEvent, id: number) => {
    e.preventDefault()
    updateMut.mutate({ id, body: editForm })
  }

  const handleToggleEnabled = (r: MaskingRule) => {
    updateMut.mutate({ id: r.id, body: { enabled: !r.enabled } })
  }

  const handleBulkCreate = async (suggestions: SuggestedMaskingRule[]) => {
    await bulkCreateMaskingRules(dsId, suggestions.map(s => ({
      name: s.name, pattern: s.pattern, replacement: s.replacement,
      level: s.level, enabled: true, sortOrder: 100,
    })))
    qc.invalidateQueries({ queryKey: ['admin-masking-rules', dsId] })
  }

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">PII 마스킹 규칙</h1>
          <p className="text-sm text-gray-500 mt-0.5">LLM 응답의 개인정보를 정규식으로 마스킹합니다.</p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={() => setShowSuggest(true)}
            className="flex items-center gap-2 border border-orange-300 hover:bg-orange-50 text-orange-700 px-3 py-2 rounded-lg text-sm font-medium"
          >
            <Shield size={15} />
            PII 자동 탐색
          </button>
          <button
            onClick={() => { setShowForm(v => !v); setEditingId(null) }}
            className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-3 py-2 rounded-lg text-sm font-medium"
          >
            <Plus size={15} /> 규칙 직접 추가
          </button>
        </div>
      </div>

      {showForm && (
        <form
          onSubmit={handleCreate}
          className="mb-6 bg-white border border-gray-200 rounded-xl p-4 grid grid-cols-2 gap-3"
        >
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">규칙 이름</label>
            <input
              required
              value={createForm.name}
              onChange={e => setCreateForm(p => ({ ...p, name: e.target.value }))}
              placeholder="예: 전화번호 마스킹"
              className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">치환 토큰</label>
            <input
              required
              value={createForm.replacement}
              onChange={e => setCreateForm(p => ({ ...p, replacement: e.target.value }))}
              placeholder="예: [전화번호]"
              className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div className="col-span-2">
            <label className="block text-xs font-medium text-gray-600 mb-1">정규식 패턴</label>
            <input
              required
              value={createForm.pattern}
              onChange={e => setCreateForm(p => ({ ...p, pattern: e.target.value }))}
              placeholder="예: 01[016789]-?\d{3,4}-?\d{4}"
              className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">레벨</label>
            <select
              value={createForm.level}
              onChange={e => setCreateForm(p => ({ ...p, level: e.target.value as 'standard' | 'aggressive' }))}
              className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="standard">기본 (standard)</option>
              <option value="aggressive">강화 (aggressive)</option>
            </select>
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">적용 순서</label>
            <input
              type="number"
              value={createForm.sortOrder}
              onChange={e => setCreateForm(p => ({ ...p, sortOrder: Number(e.target.value) }))}
              className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div className="col-span-2 flex gap-2">
            <button
              type="submit"
              disabled={createMut.isPending}
              className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-1.5 rounded-lg text-sm font-medium"
            >
              {createMut.isPending ? '추가 중…' : '추가'}
            </button>
            <button type="button" onClick={() => setShowForm(false)} className="text-gray-500 px-3 py-1.5 text-sm">
              취소
            </button>
          </div>
        </form>
      )}

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              {['규칙 이름', '정규식 패턴', '치환 토큰', '레벨', '스코프', '활성', ''].map(h => (
                <th key={h} className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wide">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {rules.map(r => (
              editingId === r.id ? (
                <tr key={r.id} className="bg-blue-50">
                  <td colSpan={7} className="px-4 py-3">
                    <form onSubmit={e => handleUpdate(e, r.id)} className="grid grid-cols-2 gap-2">
                      <div>
                        <label className="block text-xs font-medium text-gray-600 mb-1">규칙 이름</label>
                        <input
                          required
                          value={editForm.name}
                          onChange={e => setEditForm(p => ({ ...p, name: e.target.value }))}
                          className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                        />
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-gray-600 mb-1">치환 토큰</label>
                        <input
                          required
                          value={editForm.replacement}
                          onChange={e => setEditForm(p => ({ ...p, replacement: e.target.value }))}
                          className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                        />
                      </div>
                      <div className="col-span-2">
                        <label className="block text-xs font-medium text-gray-600 mb-1">정규식 패턴</label>
                        <input
                          required
                          value={editForm.pattern}
                          onChange={e => setEditForm(p => ({ ...p, pattern: e.target.value }))}
                          className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-500"
                        />
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-gray-600 mb-1">레벨</label>
                        <select
                          value={editForm.level}
                          onChange={e => setEditForm(p => ({ ...p, level: e.target.value as 'standard' | 'aggressive' }))}
                          className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                        >
                          <option value="standard">기본 (standard)</option>
                          <option value="aggressive">강화 (aggressive)</option>
                        </select>
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-gray-600 mb-1">적용 순서</label>
                        <input
                          type="number"
                          value={editForm.sortOrder}
                          onChange={e => setEditForm(p => ({ ...p, sortOrder: Number(e.target.value) }))}
                          className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                        />
                      </div>
                      <div className="col-span-2 flex gap-2 mt-1">
                        <button
                          type="submit"
                          disabled={updateMut.isPending}
                          className="flex items-center gap-1 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-3 py-1.5 rounded-lg text-sm font-medium"
                        >
                          <Check size={13} /> {updateMut.isPending ? '저장 중…' : '저장'}
                        </button>
                        <button
                          type="button"
                          onClick={() => setEditingId(null)}
                          className="flex items-center gap-1 text-gray-500 px-3 py-1.5 text-sm"
                        >
                          <X size={13} /> 취소
                        </button>
                      </div>
                    </form>
                  </td>
                </tr>
              ) : (
                <tr key={r.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 font-medium text-gray-900 text-xs">{r.name}</td>
                  <td className="px-4 py-3 font-mono text-gray-500 text-xs max-w-xs truncate">{r.pattern}</td>
                  <td className="px-4 py-3 font-mono text-gray-700 text-xs">{r.replacement}</td>
                  <td className="px-4 py-3">
                    <span className={`inline-flex px-2 py-0.5 rounded text-xs font-medium ${LEVEL_COLOR[r.level] ?? 'bg-gray-100 text-gray-600'}`}>
                      {LEVEL_LABEL[r.level] ?? r.level}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`inline-flex px-2 py-0.5 rounded text-xs font-medium ${
                      r.datasourceId == null ? 'bg-gray-100 text-gray-500' : 'bg-purple-100 text-purple-700'
                    }`}>
                      {r.datasourceId == null ? '전역' : 'DS 전용'}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <button
                      onClick={() => handleToggleEnabled(r)}
                      disabled={updateMut.isPending && editingId === null}
                      className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors focus:outline-none ${
                        r.enabled ? 'bg-green-500' : 'bg-gray-300'
                      }`}
                    >
                      <span className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white shadow transition-transform ${
                        r.enabled ? 'translate-x-4' : 'translate-x-1'
                      }`} />
                    </button>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <button
                        onClick={() => { startEdit(r); setShowForm(false) }}
                        className="text-gray-400 hover:text-blue-600"
                        title="편집"
                      >
                        <Pencil size={14} />
                      </button>
                      <button
                        onClick={() => { if (window.confirm('이 규칙을 삭제하시겠습니까?')) deleteMut.mutate(r.id) }}
                        className="text-red-400 hover:text-red-600"
                        title="삭제"
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
                  </td>
                </tr>
              )
            ))}
            {rules.length === 0 && !isLoading && (
              <tr>
                <td colSpan={7} className="px-4 py-8 text-center text-gray-400 text-sm">
                  등록된 마스킹 규칙이 없습니다. PII 자동 탐색으로 시작하세요.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {showSuggest && (
        <PiiSuggestModal
          dsId={dsId}
          onBulkCreate={handleBulkCreate}
          onClose={() => setShowSuggest(false)}
        />
      )}
    </div>
  )
}
