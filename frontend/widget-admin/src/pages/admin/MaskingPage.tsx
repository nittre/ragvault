import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, RefreshCw } from 'lucide-react'
import {
  listMaskingRules,
  createMaskingRule,
  updateMaskingRule,
  deleteMaskingRule,
  reloadMasking,
} from '../../api/admin/masking'
import type { MaskingRule } from '../../api/admin/masking'

interface ModalState {
  mode: 'create' | 'edit'
  rule?: MaskingRule
}

interface FormValues {
  name: string
  pattern: string
  replacement: string
  enabled: boolean
  ruleOrder: number
}

const defaultForm: FormValues = {
  name: '',
  pattern: '',
  replacement: '',
  enabled: true,
  ruleOrder: 1,
}

function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message
  return '알 수 없는 오류가 발생했습니다.'
}

export default function MaskingPage() {
  const qc = useQueryClient()

  const [modal, setModal] = useState<ModalState | null>(null)
  const [form, setForm] = useState<FormValues>(defaultForm)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [reloading, setReloading] = useState(false)

  const { data: rules = [], isLoading, error: listError } = useQuery({
    queryKey: ['admin-masking'],
    queryFn: listMaskingRules,
  })

  const createMut = useMutation({
    mutationFn: createMaskingRule,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-masking'] })
      closeModal()
    },
    onError: (err: unknown) => setErrorMsg(errorMessage(err)),
  })

  const updateMut = useMutation({
    mutationFn: ({ id, body }: { id: number; body: Omit<MaskingRule, 'id'> }) =>
      updateMaskingRule(id, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-masking'] })
      closeModal()
    },
    onError: (err: unknown) => setErrorMsg(errorMessage(err)),
  })

  const deleteMut = useMutation({
    mutationFn: deleteMaskingRule,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-masking'] }),
    onError: (err: unknown) => setErrorMsg(errorMessage(err)),
  })

  function openCreate() {
    setForm(defaultForm)
    setErrorMsg(null)
    setModal({ mode: 'create' })
  }

  function openEdit(rule: MaskingRule) {
    setForm({
      name: rule.name,
      pattern: rule.pattern,
      replacement: rule.replacement,
      enabled: rule.enabled,
      ruleOrder: rule.ruleOrder,
    })
    setErrorMsg(null)
    setModal({ mode: 'edit', rule })
  }

  function closeModal() {
    setModal(null)
    setForm(defaultForm)
    setErrorMsg(null)
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setErrorMsg(null)
    const body: Omit<MaskingRule, 'id'> = {
      name: form.name,
      pattern: form.pattern,
      replacement: form.replacement,
      enabled: form.enabled,
      ruleOrder: form.ruleOrder,
    }
    if (modal?.mode === 'create') {
      createMut.mutate(body)
    } else if (modal?.mode === 'edit' && modal.rule) {
      updateMut.mutate({ id: modal.rule.id, body })
    }
  }

  function handleDelete(rule: MaskingRule) {
    if (window.confirm(`"${rule.name}" 규칙을 삭제하시겠습니까?`)) {
      deleteMut.mutate(rule.id)
    }
  }

  async function handleReload() {
    setReloading(true)
    try {
      await reloadMasking()
    } catch (err) {
      setErrorMsg(errorMessage(err))
    } finally {
      setReloading(false)
    }
  }

  const isMutating = createMut.isPending || updateMut.isPending

  return (
    <div className="p-6">
      {/* 헤더 */}
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-xl font-semibold text-gray-900">PII 마스킹 규칙</h1>
        <div className="flex items-center gap-2">
          <button
            onClick={handleReload}
            disabled={reloading}
            className="flex items-center gap-2 border border-gray-300 hover:bg-gray-50 disabled:opacity-50 text-gray-700 px-3 py-2 rounded-lg text-sm font-medium transition-colors"
          >
            <RefreshCw size={14} className={reloading ? 'animate-spin' : ''} />
            캐시 재로드
          </button>
          <button
            onClick={openCreate}
            className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-3 py-2 rounded-lg text-sm font-medium transition-colors"
          >
            <Plus size={15} />
            규칙 추가
          </button>
        </div>
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

      {!isLoading && (
        <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                {['순서', '이름', '패턴', '교체값', '활성화', ''].map(h => (
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
              {rules.map(rule => (
                <tr key={rule.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 text-gray-500 text-xs w-16">{rule.ruleOrder}</td>
                  <td className="px-4 py-3 text-gray-900 text-xs font-medium">{rule.name}</td>
                  <td className="px-4 py-3 max-w-xs">
                    <span className="block truncate font-mono text-xs text-gray-600">{rule.pattern}</span>
                  </td>
                  <td className="px-4 py-3 text-gray-500 text-xs font-mono">{rule.replacement}</td>
                  <td className="px-4 py-3">
                    <span
                      className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${
                        rule.enabled
                          ? 'bg-green-100 text-green-700'
                          : 'bg-gray-100 text-gray-500'
                      }`}
                    >
                      {rule.enabled ? '활성' : '비활성'}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-3 justify-end">
                      <button
                        onClick={() => openEdit(rule)}
                        className="text-blue-500 hover:text-blue-700 text-xs transition-colors"
                      >
                        편집
                      </button>
                      <button
                        onClick={() => handleDelete(rule)}
                        disabled={deleteMut.isPending}
                        className="text-red-400 hover:text-red-600 text-xs transition-colors disabled:opacity-50"
                      >
                        삭제
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {rules.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-4 py-8 text-center text-gray-400 text-sm">
                    마스킹 규칙이 없습니다.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {/* 생성/편집 모달 */}
      {modal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-xl shadow-xl w-full max-w-lg mx-4 flex flex-col max-h-[90vh]">
            {/* 모달 헤더 */}
            <div className="px-6 py-4 border-b border-gray-200 flex items-center justify-between shrink-0">
              <h2 className="text-base font-semibold text-gray-900">
                {modal.mode === 'create' ? '규칙 추가' : '규칙 편집'}
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
                {/* 이름 */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">
                    이름 <span className="text-red-400">*</span>
                  </label>
                  <input
                    required
                    value={form.name}
                    onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                    placeholder="예: 전화번호 마스킹"
                    className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-full focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>

                {/* 패턴 */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">
                    패턴 (정규식) <span className="text-red-400">*</span>
                  </label>
                  <input
                    required
                    value={form.pattern}
                    onChange={e => setForm(f => ({ ...f, pattern: e.target.value }))}
                    placeholder="예: \d{3}-\d{4}-\d{4}"
                    className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-full font-mono focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>

                {/* 교체값 */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">
                    교체값 <span className="text-red-400">*</span>
                  </label>
                  <input
                    required
                    value={form.replacement}
                    onChange={e => setForm(f => ({ ...f, replacement: e.target.value }))}
                    placeholder="예: ***-****-****"
                    className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-full font-mono focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>

                {/* 순서 */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">
                    순서 <span className="text-red-400">*</span>
                  </label>
                  <input
                    required
                    type="number"
                    min={1}
                    value={form.ruleOrder}
                    onChange={e => setForm(f => ({ ...f, ruleOrder: Number(e.target.value) }))}
                    className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-24 focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>

                {/* 활성화 */}
                <div className="flex items-center gap-2">
                  <input
                    id="enabled-checkbox"
                    type="checkbox"
                    checked={form.enabled}
                    onChange={e => setForm(f => ({ ...f, enabled: e.target.checked }))}
                    className="w-4 h-4 text-blue-600 border-gray-300 rounded focus:ring-blue-500"
                  />
                  <label htmlFor="enabled-checkbox" className="text-sm text-gray-700 select-none">
                    활성화
                  </label>
                </div>

                {/* 모달 내 에러 */}
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
