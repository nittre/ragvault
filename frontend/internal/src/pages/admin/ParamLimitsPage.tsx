import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Lock, Pencil, Unlock } from 'lucide-react'
import {
  getParamLimits,
  lockParam,
  unlockParam,
  updateParamLimit,
  type ParamLimit,
} from '../../api/admin/paramLimits'
import EditParamLimitModal, { type EditParamLimitInput } from '../../components/admin/EditParamLimitModal'

/** axios 에러에서 서버가 내려준 메시지를 최대한 사람이 읽을 수 있는 형태로 추출. */
function extractErrorMessage(err: unknown): string {
  const anyErr = err as { response?: { data?: unknown }; message?: string }
  const data = anyErr?.response?.data
  if (typeof data === 'string') return data
  if (data && typeof data === 'object' && 'message' in data && typeof (data as { message?: unknown }).message === 'string') {
    return (data as { message: string }).message
  }
  return anyErr?.message ?? '알 수 없는 오류가 발생했습니다.'
}

export default function ParamLimitsPage() {
  const qc = useQueryClient()

  const { data: limits = [], isLoading, error } = useQuery({
    queryKey: ['admin-param-limits'],
    queryFn: getParamLimits,
  })

  const [mutationError, setMutationError] = useState<string | null>(null)
  const [editingLimit, setEditingLimit] = useState<ParamLimit | null>(null)

  const saveMut = useMutation({
    mutationFn: async (input: EditParamLimitInput & { paramName: string; wasLocked: boolean }) => {
      const body: { minValue?: number; maxValue?: number; defaultValue?: string | null } = {
        defaultValue: input.defaultValue === '' ? null : input.defaultValue,
      }
      if (input.minValue !== '') body.minValue = Number(input.minValue)
      if (input.maxValue !== '') body.maxValue = Number(input.maxValue)
      await updateParamLimit(input.paramName, body)

      if (input.locked) {
        await lockParam(input.paramName, {
          reason: input.lockedReason,
          fixedValue: Number(input.fixedValue),
        })
      } else if (input.wasLocked) {
        await unlockParam(input.paramName)
      }
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-param-limits'] })
      setMutationError(null)
      setEditingLimit(null)
    },
    onError: err => setMutationError(extractErrorMessage(err)),
  })

  const handleSave = (input: EditParamLimitInput) => {
    if (!editingLimit) return
    saveMut.mutate({ ...input, paramName: editingLimit.paramName, wasLocked: editingLimit.locked })
  }

  return (
    <div className="p-6">
      <div className="mb-6">
        <h1 className="text-xl font-semibold text-gray-900">파라미터 한도</h1>
        <p className="text-sm text-gray-500 mt-0.5">
          사용자가 조정 가능한 RAG 파라미터의 허용 범위를 설정합니다.
        </p>
      </div>

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}
      {mutationError && !editingLimit && (
        <p className="text-sm text-red-500 mb-2">
          저장 실패: {mutationError}
          <button onClick={() => setMutationError(null)} className="ml-2 text-gray-400 hover:text-gray-600">
            닫기
          </button>
        </p>
      )}

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              {['파라미터', '설명', '기본값', '최솟값', '최댓값', '고정값', '잠금 여부', '잠금 사유', ''].map(h => (
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
            {limits.map(lim => (
              <tr key={lim.id} className={`hover:bg-gray-50 ${lim.locked ? 'bg-orange-50/30' : ''}`}>
                <td className="px-4 py-3 font-mono text-gray-900 text-xs">{lim.paramName}</td>
                <td className="px-4 py-3 text-gray-500 text-xs">{lim.description || '-'}</td>
                <td className="px-4 py-3">
                  <span className={lim.defaultValue == null ? 'text-red-500 font-medium' : 'text-gray-700'}>
                    {lim.defaultValue ?? '미설정 ⚠️'}
                  </span>
                </td>
                <td className="px-4 py-3 text-gray-700">{lim.minValue ?? '-'}</td>
                <td className="px-4 py-3 text-gray-700">{lim.maxValue ?? '-'}</td>
                <td className="px-4 py-3 text-gray-700">{lim.fixedValue ?? '-'}</td>
                <td className="px-4 py-3">
                  {lim.locked ? (
                    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium bg-orange-100 text-orange-700">
                      <Lock size={11} /> 잠김
                    </span>
                  ) : (
                    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium bg-gray-100 text-gray-500">
                      <Unlock size={11} /> 개방
                    </span>
                  )}
                </td>
                <td className="px-4 py-3 text-gray-400 text-xs">{lim.lockedReason || '-'}</td>
                <td className="px-4 py-3">
                  <button
                    onClick={() => setEditingLimit(lim)}
                    className="inline-flex items-center gap-1 text-blue-500 hover:text-blue-700 text-xs"
                  >
                    <Pencil size={11} /> 편집
                  </button>
                </td>
              </tr>
            ))}
            {limits.length === 0 && !isLoading && (
              <tr>
                <td colSpan={9} className="px-4 py-8 text-center text-gray-400 text-sm">
                  파라미터 한도 데이터가 없습니다.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {editingLimit && (
        <EditParamLimitModal
          limit={editingLimit}
          onClose={() => {
            setEditingLimit(null)
            setMutationError(null)
          }}
          onSave={handleSave}
          saving={saveMut.isPending}
          error={mutationError}
        />
      )}
    </div>
  )
}
