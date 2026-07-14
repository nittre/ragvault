import { useState } from 'react'
import { X } from 'lucide-react'
import type { ParamLimit } from '../../api/admin/paramLimits'

export interface EditParamLimitInput {
  minValue: string
  maxValue: string
  defaultValue: string
  locked: boolean
  fixedValue: string
  lockedReason: string
}

interface Props {
  limit: ParamLimit
  onSave: (input: EditParamLimitInput) => void
  onClose: () => void
  saving: boolean
  error: string | null
}

export default function EditParamLimitModal({ limit, onSave, onClose, saving, error }: Props) {
  const [defaultValue, setDefaultValue] = useState(limit.defaultValue ?? '')
  const [minValue, setMinValue] = useState(limit.minValue != null ? String(limit.minValue) : '')
  const [maxValue, setMaxValue] = useState(limit.maxValue != null ? String(limit.maxValue) : '')
  const [locked, setLocked] = useState(limit.locked)
  const [fixedValue, setFixedValue] = useState(limit.fixedValue != null ? String(limit.fixedValue) : '')
  const [lockedReason, setLockedReason] = useState(limit.lockedReason ?? '')
  const [validationError, setValidationError] = useState<string | null>(null)

  const handleSubmit = () => {
    if (locked && fixedValue === '') {
      setValidationError('잠금 설정 시 고정값을 입력해야 합니다.')
      return
    }
    if (minValue !== '' && maxValue !== '' && Number(minValue) > Number(maxValue)) {
      setValidationError('최솟값은 최댓값보다 클 수 없습니다.')
      return
    }
    setValidationError(null)
    onSave({ minValue, maxValue, defaultValue, locked, fixedValue, lockedReason })
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="bg-white rounded-xl shadow-2xl w-[440px] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-200">
          <div>
            <h2 className="text-sm font-semibold text-gray-900 font-mono">{limit.paramName}</h2>
            {limit.description && <p className="text-xs text-gray-500 mt-0.5">{limit.description}</p>}
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600">
            <X size={18} />
          </button>
        </div>

        {/* Content */}
        <div className="px-5 py-4 space-y-4">
          {(validationError || error) && (
            <p className="text-xs text-red-500 bg-red-50 border border-red-200 rounded px-3 py-2">
              {validationError ?? error}
            </p>
          )}

          <div>
            <label className="block text-xs font-medium text-gray-700 mb-1">기본값 (Stage 1)</label>
            <input
              type="text"
              value={defaultValue}
              onChange={e => setDefaultValue(e.target.value)}
              placeholder="비우면 미설정 — 이 파라미터를 쓰는 요청이 전부 실패합니다"
              className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-gray-700 mb-1">최솟값</label>
              <input
                type="number"
                value={minValue}
                onChange={e => setMinValue(e.target.value)}
                className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-700 mb-1">최댓값</label>
              <input
                type="number"
                value={maxValue}
                onChange={e => setMaxValue(e.target.value)}
                className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
          </div>

          <div className="border-t border-gray-100 pt-3">
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={locked}
                onChange={e => setLocked(e.target.checked)}
                className="rounded border-gray-300 text-orange-600 focus:ring-orange-500"
              />
              <span className="text-sm font-medium text-gray-700">
                잠금 (Guard B — 사용자가 변경 불가, 고정값 강제)
              </span>
            </label>

            {locked && (
              <div className="mt-3 space-y-3 pl-6">
                <div>
                  <label className="block text-xs font-medium text-gray-700 mb-1">고정값</label>
                  <input
                    type="number"
                    value={fixedValue}
                    onChange={e => setFixedValue(e.target.value)}
                    className="w-full border border-orange-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-orange-500"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-gray-700 mb-1">
                    잠금 사유 <span className="text-orange-500">(사용자에게 노출됩니다)</span>
                  </label>
                  <input
                    type="text"
                    value={lockedReason}
                    onChange={e => setLockedReason(e.target.value)}
                    className="w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-2 px-5 py-3.5 border-t border-gray-200 bg-gray-50 rounded-b-xl">
          <button
            onClick={onClose}
            className="px-4 py-1.5 text-sm text-gray-600 hover:text-gray-800 border border-gray-300 rounded-lg hover:bg-white"
          >
            취소
          </button>
          <button
            onClick={handleSubmit}
            disabled={saving}
            className="px-4 py-1.5 text-sm font-medium bg-blue-600 hover:bg-blue-700 disabled:opacity-40 text-white rounded-lg"
          >
            {saving ? '저장 중…' : '저장'}
          </button>
        </div>
      </div>
    </div>
  )
}
