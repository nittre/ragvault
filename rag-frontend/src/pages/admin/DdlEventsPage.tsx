import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, CheckCircle, XCircle } from 'lucide-react'
import { getDdlEvents, dismissDdlEvent, type DdlEvent } from '../../api/admin/ddlEvents'

function impactColor(score: number) {
  if (score >= 8) return 'text-red-600 bg-red-50 border-red-200'
  if (score >= 5) return 'text-orange-600 bg-orange-50 border-orange-200'
  return 'text-yellow-600 bg-yellow-50 border-yellow-200'
}

export default function DdlEventsPage() {
  const qc = useQueryClient()

  const { data: events = [], isLoading, error } = useQuery({
    queryKey: ['admin-ddl-events'],
    queryFn: getDdlEvents,
  })

  const dismissMut = useMutation({
    mutationFn: dismissDdlEvent,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-ddl-events'] }),
  })

  return (
    <div className="p-6">
      <div className="mb-6">
        <h1 className="text-xl font-semibold text-gray-900">DDL 이벤트</h1>
        <p className="text-sm text-gray-500 mt-0.5">
          MySQL 스키마 변경 이벤트 및 RAG 시스템 영향 분석 결과입니다.
        </p>
      </div>

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      {events.length === 0 && !isLoading && (
        <div className="bg-white border border-gray-200 rounded-xl p-12 text-center">
          <CheckCircle size={36} className="mx-auto text-green-400 mb-3" />
          <p className="text-gray-500 text-sm">처리 대기 중인 DDL 이벤트가 없습니다.</p>
        </div>
      )}

      <div className="space-y-4">
        {events.map((ev: DdlEvent) => (
          <div key={ev.id} className="bg-white border border-gray-200 rounded-xl p-5">
            <div className="flex items-start justify-between gap-4">
              <div className="flex items-start gap-3 flex-1">
                <AlertTriangle size={18} className="mt-0.5 text-orange-500 flex-shrink-0" />
                <div className="flex-1">
                  <div className="flex items-center gap-2 mb-1 flex-wrap">
                    <span className="font-mono text-sm font-semibold text-gray-900">
                      {ev.tableName}
                    </span>
                    <span className="text-xs bg-blue-100 text-blue-700 px-2 py-0.5 rounded">
                      {ev.eventType}
                    </span>
                    <span
                      className={`text-xs px-2 py-0.5 rounded font-medium border ${impactColor(ev.impactScore)}`}
                    >
                      영향도 {ev.impactScore}/10
                    </span>
                  </div>
                  {ev.analysisResult && (
                    <p className="text-sm text-gray-600 mt-2 whitespace-pre-line">
                      {ev.analysisResult}
                    </p>
                  )}
                  <p className="text-xs text-gray-400 mt-2">
                    {new Date(ev.createdAt).toLocaleString()}
                  </p>
                </div>
              </div>
              <button
                onClick={() => {
                  if (window.confirm('이 이벤트를 처리 완료로 표시하시겠습니까?')) {
                    dismissMut.mutate(ev.id)
                  }
                }}
                disabled={dismissMut.isPending}
                className="flex items-center gap-1 border border-gray-300 hover:bg-gray-50 text-gray-600 px-3 py-1.5 rounded-lg text-xs font-medium flex-shrink-0"
              >
                <XCircle size={13} /> 처리 완료
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
