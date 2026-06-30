import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { X, Shield, ChevronDown, ChevronRight } from 'lucide-react'
import { suggestMaskingRules, type SuggestedMaskingRule } from '../../api/admin/maskingRules'

interface Props {
  dsId: number
  onBulkCreate: (rules: SuggestedMaskingRule[]) => Promise<void>
  onClose: () => void
}

const LEVEL_LABEL: Record<string, string> = {
  standard: '기본',
  aggressive: '강화',
}

const LEVEL_COLOR: Record<string, string> = {
  standard: 'bg-blue-100 text-blue-700',
  aggressive: 'bg-orange-100 text-orange-700',
}

export default function PiiSuggestModal({ dsId, onBulkCreate, onClose }: Props) {
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [submitting, setSubmitting] = useState(false)

  const { data: suggestions = [], isLoading } = useQuery({
    queryKey: ['admin-pii-suggest', dsId],
    queryFn: () => suggestMaskingRules(dsId),
  })

  const toggle = (name: string) => {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(name)) next.delete(name)
      else next.add(name)
      return next
    })
  }

  const toggleExpand = (name: string) => {
    setExpanded(prev => {
      const next = new Set(prev)
      if (next.has(name)) next.delete(name)
      else next.add(name)
      return next
    })
  }

  const toggleAll = () => {
    if (selected.size === suggestions.length) {
      setSelected(new Set())
    } else {
      setSelected(new Set(suggestions.map(s => s.name)))
    }
  }

  const handleSubmit = async () => {
    const chosen = suggestions.filter(s => selected.has(s.name))
    if (chosen.length === 0) return
    setSubmitting(true)
    try {
      await onBulkCreate(chosen)
      onClose()
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="bg-white rounded-xl shadow-2xl w-[680px] max-h-[78vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-200">
          <div className="flex items-center gap-2.5">
            <Shield size={18} className="text-orange-500" />
            <div>
              <h2 className="text-sm font-semibold text-gray-900">PII 컬럼 자동 탐색</h2>
              <p className="text-xs text-gray-500 mt-0.5">
                스키마 컬럼명 분석으로 감지된 PII 유형별 마스킹 규칙을 선택하세요.
              </p>
            </div>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600">
            <X size={18} />
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto px-5 py-3 space-y-2">
          {isLoading ? (
            <div className="flex items-center justify-center h-32 text-sm text-gray-400">
              PII 분석 중…
            </div>
          ) : suggestions.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-32 gap-2">
              <Shield size={24} className="text-green-400" />
              <p className="text-sm text-gray-500">탐색된 PII 컬럼이 없거나 이미 모두 규칙이 있습니다.</p>
            </div>
          ) : (
            <>
              <div className="flex items-center gap-2 pb-1 border-b border-gray-100">
                <input
                  type="checkbox"
                  checked={selected.size === suggestions.length && suggestions.length > 0}
                  onChange={toggleAll}
                  className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
                <span className="text-xs text-gray-500">전체 선택</span>
              </div>
              {suggestions.map(s => (
                <div key={s.name} className="border border-gray-200 rounded-lg overflow-hidden">
                  <div className="flex items-center gap-3 px-4 py-3 bg-white hover:bg-gray-50">
                    <input
                      type="checkbox"
                      checked={selected.has(s.name)}
                      onChange={() => toggle(s.name)}
                      className="rounded border-gray-300 text-blue-600 focus:ring-blue-500 shrink-0"
                    />
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="text-sm font-medium text-gray-900">{s.name}</span>
                        <span className={`inline-flex px-2 py-0.5 rounded text-xs font-medium ${LEVEL_COLOR[s.level]}`}>
                          {LEVEL_LABEL[s.level]}
                        </span>
                        <span className="text-xs text-gray-400">{s.detectedInColumns.length}개 컬럼에서 감지</span>
                      </div>
                      <p className="text-xs text-gray-500 font-mono mt-0.5 truncate">{s.pattern}</p>
                    </div>
                    <button
                      onClick={() => toggleExpand(s.name)}
                      className="text-gray-400 hover:text-gray-600 shrink-0"
                    >
                      {expanded.has(s.name) ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                    </button>
                  </div>
                  {expanded.has(s.name) && (
                    <div className="px-4 pb-3 bg-gray-50 border-t border-gray-100">
                      <div className="flex flex-wrap gap-x-1 gap-y-0.5 mt-2">
                        <span className="text-xs text-gray-400 mr-1">감지된 컬럼:</span>
                        {s.detectedInColumns.map(col => (
                          <span key={col} className="font-mono text-xs text-orange-600 bg-orange-50 px-1.5 py-0.5 rounded">
                            {col}
                          </span>
                        ))}
                      </div>
                      <div className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-gray-600">
                        <div>
                          <span className="text-gray-400">치환 토큰: </span>
                          <span className="font-mono font-medium">{s.replacement}</span>
                        </div>
                        <div>
                          <span className="text-gray-400">레벨: </span>
                          <span className={`px-1.5 py-0.5 rounded text-xs font-medium ${LEVEL_COLOR[s.level]}`}>
                            {LEVEL_LABEL[s.level]}
                          </span>
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between px-5 py-3.5 border-t border-gray-200 bg-gray-50 rounded-b-xl">
          <span className="text-xs text-gray-500">
            {suggestions.length > 0
              ? `${suggestions.length}개 PII 유형 감지됨`
              : '감지된 PII 없음'}
          </span>
          <div className="flex gap-2">
            <button
              onClick={onClose}
              className="px-4 py-1.5 text-sm text-gray-600 hover:text-gray-800 border border-gray-300 rounded-lg hover:bg-white"
            >
              취소
            </button>
            <button
              onClick={handleSubmit}
              disabled={selected.size === 0 || submitting}
              className="px-4 py-1.5 text-sm font-medium bg-orange-500 hover:bg-orange-600 disabled:opacity-40 text-white rounded-lg"
            >
              {submitting ? '생성 중…' : `선택 규칙 생성 (${selected.size}개)`}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
