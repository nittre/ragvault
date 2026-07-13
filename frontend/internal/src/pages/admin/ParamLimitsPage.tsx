import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Lock, Unlock, Check, X, Pencil } from 'lucide-react'
import { getParamLimits, updateParamLimit, lockParam, unlockParam } from '../../api/admin/paramLimits'

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

  const updateMut = useMutation({
    mutationFn: (
      { paramName, body }: {
        paramName: string
        body: { minValue?: number; maxValue?: number; fixedValue?: number | null; defaultValue?: string | null }
      }
    ) => updateParamLimit(paramName, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-param-limits'] })
      setMutationError(null)
      setEditing(null)
      setEditingFixed(null)
      setEditingDefault(null)
    },
    onError: err => setMutationError(extractErrorMessage(err)),
  })

  const lockMut = useMutation({
    mutationFn: ({ paramKey, reason }: { paramKey: string; reason?: string }) =>
      lockParam(paramKey, { reason }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-param-limits'] })
      setMutationError(null)
      setEditingReason(null)
    },
    onError: err => setMutationError(extractErrorMessage(err)),
  })

  const unlockMut = useMutation({
    mutationFn: unlockParam,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-param-limits'] })
      setMutationError(null)
    },
    onError: err => setMutationError(extractErrorMessage(err)),
  })

  const [editing, setEditing] = useState<string | null>(null)
  const [editMin, setEditMin] = useState('')
  const [editMax, setEditMax] = useState('')
  const [editingReason, setEditingReason] = useState<string | null>(null)
  const [editReasonValue, setEditReasonValue] = useState('')
  const [editingFixed, setEditingFixed] = useState<string | null>(null)
  const [editFixedValue, setEditFixedValue] = useState('')
  const [editingDefault, setEditingDefault] = useState<string | null>(null)
  const [editDefaultValue, setEditDefaultValue] = useState('')

  const startEdit = (paramName: string, min: number | null, max: number | null) => {
    setEditing(paramName)
    setEditMin(min != null ? String(min) : '')
    setEditMax(max != null ? String(max) : '')
  }

  const handleSave = (paramName: string) => {
    updateMut.mutate({ paramName, body: { minValue: Number(editMin), maxValue: Number(editMax) } })
  }

  const handleLock = (paramKey: string) => {
    if (window.confirm('해당 파라미터를 잠그시겠습니까?')) {
      lockMut.mutate({ paramKey })
    }
  }

  const startEditReason = (paramName: string, currentReason: string) => {
    setEditingReason(paramName)
    setEditReasonValue(currentReason || '')
  }

  const handleSaveReason = (paramName: string) => {
    lockMut.mutate({ paramKey: paramName, reason: editReasonValue })
  }

  const startEditFixed = (paramName: string, currentFixed: number | null) => {
    setEditingFixed(paramName)
    setEditFixedValue(currentFixed != null ? String(currentFixed) : '')
  }

  const handleSaveFixed = (paramName: string) => {
    updateMut.mutate({
      paramName,
      body: { fixedValue: editFixedValue === '' ? null : Number(editFixedValue) },
    })
  }

  const startEditDefault = (paramName: string, currentDefault: string | null) => {
    setEditingDefault(paramName)
    setEditDefaultValue(currentDefault ?? '')
  }

  const handleSaveDefault = (paramName: string) => {
    updateMut.mutate({
      paramName,
      body: { defaultValue: editDefaultValue === '' ? null : editDefaultValue },
    })
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
      {mutationError && (
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
                  {editingDefault === lim.paramName ? (
                    <div className="flex items-center gap-1">
                      <input
                        type="text"
                        value={editDefaultValue}
                        onChange={e => setEditDefaultValue(e.target.value)}
                        placeholder="비우면 미설정"
                        className="border border-blue-400 rounded px-2 py-1 text-sm w-20 focus:outline-none"
                      />
                      <button
                        onClick={() => handleSaveDefault(lim.paramName)}
                        disabled={updateMut.isPending}
                        className="text-green-600 hover:text-green-800 p-1"
                      >
                        <Check size={12} />
                      </button>
                      <button
                        onClick={() => setEditingDefault(null)}
                        className="text-gray-400 hover:text-gray-600 p-1"
                      >
                        <X size={12} />
                      </button>
                    </div>
                  ) : (
                    <div className="flex items-center gap-1">
                      <span className={lim.defaultValue == null ? 'text-red-500 font-medium' : 'text-gray-700'}>
                        {lim.defaultValue ?? '미설정 ⚠️'}
                      </span>
                      <button
                        onClick={() => startEditDefault(lim.paramName, lim.defaultValue)}
                        className="text-gray-300 hover:text-blue-500 p-0.5"
                        title="Stage 1 기본값 수정 — 미설정 시 이 파라미터를 쓰는 요청이 전부 실패합니다"
                      >
                        <Pencil size={11} />
                      </button>
                    </div>
                  )}
                </td>
                <td className="px-4 py-3">
                  {editing === lim.paramName ? (
                    <input
                      type="number"
                      value={editMin}
                      onChange={e => setEditMin(e.target.value)}
                      className="border border-blue-400 rounded px-2 py-1 text-sm w-20 focus:outline-none"
                    />
                  ) : (
                    <span className="text-gray-700">{lim.minValue ?? '-'}</span>
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
                    <span className="text-gray-700">{lim.maxValue ?? '-'}</span>
                  )}
                </td>
                <td className="px-4 py-3">
                  {editingFixed === lim.paramName ? (
                    <div className="flex items-center gap-1">
                      <input
                        type="number"
                        value={editFixedValue}
                        onChange={e => setEditFixedValue(e.target.value)}
                        placeholder="비우면 해제"
                        className="border border-blue-400 rounded px-2 py-1 text-sm w-20 focus:outline-none"
                      />
                      <button
                        onClick={() => handleSaveFixed(lim.paramName)}
                        disabled={updateMut.isPending}
                        className="text-green-600 hover:text-green-800 p-1"
                      >
                        <Check size={12} />
                      </button>
                      <button
                        onClick={() => setEditingFixed(null)}
                        className="text-gray-400 hover:text-gray-600 p-1"
                      >
                        <X size={12} />
                      </button>
                    </div>
                  ) : (
                    <div className="flex items-center gap-1">
                      <span className="text-gray-700">{lim.fixedValue ?? '-'}</span>
                      <button
                        onClick={() => startEditFixed(lim.paramName, lim.fixedValue)}
                        className="text-gray-300 hover:text-blue-500 p-0.5"
                        title="고정값 수정 — 채팅 화면 잠금 필드에 표시되는 값"
                      >
                        <Pencil size={11} />
                      </button>
                    </div>
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
                <td className="px-4 py-3 text-gray-400 text-xs">
                  {editingReason === lim.paramName ? (
                    <div className="flex flex-col gap-1">
                      <span className="text-[11px] text-orange-500">
                        ⚠️ 이 사유는 사용자에게 노출됩니다
                      </span>
                      <div className="flex items-center gap-1">
                        <input
                          type="text"
                          value={editReasonValue}
                          onChange={e => setEditReasonValue(e.target.value)}
                          className="border border-blue-400 rounded px-2 py-1 text-xs w-40 focus:outline-none"
                        />
                        <button
                          onClick={() => handleSaveReason(lim.paramName)}
                          disabled={lockMut.isPending}
                          className="text-green-600 hover:text-green-800 p-1"
                        >
                          <Check size={12} />
                        </button>
                        <button
                          onClick={() => setEditingReason(null)}
                          className="text-gray-400 hover:text-gray-600 p-1"
                        >
                          <X size={12} />
                        </button>
                      </div>
                    </div>
                  ) : (
                    <div className="flex items-center gap-1">
                      <span>{lim.lockedReason || '-'}</span>
                      {lim.locked && (
                        <button
                          onClick={() => startEditReason(lim.paramName, lim.lockedReason)}
                          className="text-gray-300 hover:text-blue-500 p-0.5"
                          title="잠금 사유 수정"
                        >
                          <Pencil size={11} />
                        </button>
                      )}
                    </div>
                  )}
                </td>
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
                <td colSpan={9} className="px-4 py-8 text-center text-gray-400 text-sm">
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
