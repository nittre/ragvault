import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Lock, Unlock, Check, X } from 'lucide-react'
import { getParamLimits, updateParamLimit, lockParam, unlockParam } from '../../api/admin/paramLimits'

export default function ParamLimitsPage() {
  const qc = useQueryClient()

  const { data: limits = [], isLoading, error } = useQuery({
    queryKey: ['admin-param-limits'],
    queryFn: getParamLimits,
  })

  const updateMut = useMutation({
    mutationFn: ({ paramName, body }: { paramName: string; body: { minValue?: number; maxValue?: number } }) =>
      updateParamLimit(paramName, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-param-limits'] })
      setEditing(null)
    },
  })

  const lockMut = useMutation({
    mutationFn: ({ paramKey, reason }: { paramKey: string; reason: string }) =>
      lockParam(paramKey, { lockedReason: reason }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-param-limits'] }),
  })

  const unlockMut = useMutation({
    mutationFn: unlockParam,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-param-limits'] }),
  })

  const [editing, setEditing] = useState<string | null>(null)
  const [editMin, setEditMin] = useState('')
  const [editMax, setEditMax] = useState('')

  const startEdit = (paramName: string, min: number, max: number) => {
    setEditing(paramName)
    setEditMin(String(min))
    setEditMax(String(max))
  }

  const handleSave = (paramName: string) => {
    updateMut.mutate({ paramName, body: { minValue: Number(editMin), maxValue: Number(editMax) } })
  }

  const handleLock = (paramKey: string) => {
    const reason = window.prompt('잠금 사유를 입력하세요.')
    if (reason !== null) {
      lockMut.mutate({ paramKey, reason })
    }
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

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              {['파라미터', '최솟값', '최댓값', '잠금 여부', '잠금 사유', ''].map(h => (
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
                <td className="px-4 py-3">
                  {editing === lim.paramName ? (
                    <input
                      type="number"
                      value={editMin}
                      onChange={e => setEditMin(e.target.value)}
                      className="border border-blue-400 rounded px-2 py-1 text-sm w-20 focus:outline-none"
                    />
                  ) : (
                    <span className="text-gray-700">{lim.minValue}</span>
                  )}
                </td>
                <td className="px-4 py-3">
                  {editing === lim.paramName ? (
                    <input
                      type="number"
                      value={editMax}
                      onChange={e => setEditMax(e.target.value)}
                      className="border border-blue-400 rounded px-2 py-1 text-sm w-20 focus:outline-none"
                    />
                  ) : (
                    <span className="text-gray-700">{lim.maxValue}</span>
                  )}
                </td>
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
                  <div className="flex items-center gap-2">
                    {editing === lim.paramName ? (
                      <>
                        <button
                          onClick={() => handleSave(lim.paramName)}
                          disabled={updateMut.isPending}
                          className="text-green-600 hover:text-green-800 p-1"
                        >
                          <Check size={14} />
                        </button>
                        <button
                          onClick={() => setEditing(null)}
                          className="text-gray-400 hover:text-gray-600 p-1"
                        >
                          <X size={14} />
                        </button>
                      </>
                    ) : (
                      <button
                        onClick={() => startEdit(lim.paramName, lim.minValue, lim.maxValue)}
                        className="text-blue-500 hover:text-blue-700 text-xs"
                      >
                        편집
                      </button>
                    )}
                    {lim.locked ? (
                      <button
                        onClick={() => {
                          if (window.confirm('잠금을 해제하시겠습니까?')) unlockMut.mutate(lim.paramName)
                        }}
                        className="text-orange-500 hover:text-orange-700"
                        title="잠금 해제"
                      >
                        <Unlock size={13} />
                      </button>
                    ) : (
                      <button
                        onClick={() => handleLock(lim.paramName)}
                        className="text-gray-400 hover:text-orange-600"
                        title="잠금"
                      >
                        <Lock size={13} />
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
            {limits.length === 0 && !isLoading && (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-gray-400 text-sm">
                  파라미터 한도 데이터가 없습니다.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
