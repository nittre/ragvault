import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, Trash2 } from 'lucide-react'
import { getMaskingRules, createMaskingRule, deleteMaskingRule, type MaskType } from '../../api/admin/maskingRules'

const MASK_TYPES: MaskType[] = ['FULL_MASK', 'PARTIAL_MASK', 'EMAIL_MASK', 'PHONE_MASK']
const MASK_LABELS: Record<MaskType, string> = {
  FULL_MASK: '전체 마스킹',
  PARTIAL_MASK: '부분 마스킹',
  EMAIL_MASK: '이메일 마스킹',
  PHONE_MASK: '전화번호 마스킹',
}

export default function MaskingRulesPage() {
  const qc = useQueryClient()

  const { data: rules = [], isLoading, error } = useQuery({
    queryKey: ['admin-masking-rules'],
    queryFn: getMaskingRules,
  })

  const createMut = useMutation({
    mutationFn: createMaskingRule,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-masking-rules'] })
      setShowForm(false)
      setForm({ columnPattern: '', maskType: 'FULL_MASK' })
    },
  })

  const deleteMut = useMutation({
    mutationFn: deleteMaskingRule,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-masking-rules'] }),
  })

  const [showForm, setShowForm] = useState(false)
  const [form, setForm] = useState<{ columnPattern: string; maskType: MaskType }>({
    columnPattern: '',
    maskType: 'FULL_MASK',
  })

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault()
    createMut.mutate(form)
  }

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">PII 마스킹 규칙</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            컬럼 패턴 기반으로 SQL 응답의 개인정보를 마스킹합니다.
          </p>
        </div>
        <button
          onClick={() => setShowForm(v => !v)}
          className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-3 py-2 rounded-lg text-sm font-medium"
        >
          <Plus size={15} /> 규칙 추가
        </button>
      </div>

      {showForm && (
        <form
          onSubmit={handleCreate}
          className="mb-6 bg-white border border-gray-200 rounded-xl p-4 flex flex-wrap gap-3 items-end"
        >
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">컬럼 패턴 (정규식)</label>
            <input
              required
              value={form.columnPattern}
              onChange={e => setForm(p => ({ ...p, columnPattern: e.target.value }))}
              placeholder="예: .*phone.*"
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-56 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">마스킹 타입</label>
            <select
              value={form.maskType}
              onChange={e => setForm(p => ({ ...p, maskType: e.target.value as MaskType }))}
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              {MASK_TYPES.map(t => (
                <option key={t} value={t}>{MASK_LABELS[t]}</option>
              ))}
            </select>
          </div>
          <button
            type="submit"
            disabled={createMut.isPending}
            className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-1.5 rounded-lg text-sm font-medium"
          >
            {createMut.isPending ? '추가 중…' : '추가'}
          </button>
          <button
            type="button"
            onClick={() => setShowForm(false)}
            className="text-gray-500 px-3 py-1.5 text-sm"
          >
            취소
          </button>
        </form>
      )}

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              {['컬럼 패턴', '마스킹 타입', '상태', '생성일', ''].map(h => (
                <th
                  key={h}
                  className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wide"
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {rules.map(r => (
              <tr key={r.id} className="hover:bg-gray-50">
                <td className="px-4 py-3 font-mono text-gray-900 text-xs">{r.columnPattern}</td>
                <td className="px-4 py-3">
                  <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-orange-100 text-orange-700">
                    {MASK_LABELS[r.maskType] ?? r.maskType}
                  </span>
                </td>
                <td className="px-4 py-3">
                  <span
                    className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${
                      r.active ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
                    }`}
                  >
                    {r.active ? '활성' : '비활성'}
                  </span>
                </td>
                <td className="px-4 py-3 text-gray-400 text-xs">
                  {new Date(r.createdAt).toLocaleDateString()}
                </td>
                <td className="px-4 py-3">
                  <button
                    onClick={() => {
                      if (window.confirm('이 규칙을 삭제하시겠습니까?')) deleteMut.mutate(r.id)
                    }}
                    className="text-red-400 hover:text-red-600"
                  >
                    <Trash2 size={14} />
                  </button>
                </td>
              </tr>
            ))}
            {rules.length === 0 && !isLoading && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-gray-400 text-sm">
                  등록된 마스킹 규칙이 없습니다.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
