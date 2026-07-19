import { useParams, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Database, CheckCircle, ArrowLeft } from 'lucide-react'
import { getDdlEvents, type DdlEvent } from '../../api/admin/ddlEvents'

function riskBadge(riskLevel: string | null) {
  if (riskLevel === 'HIGH') return 'text-red-600 bg-red-50 border-red-200'
  if (riskLevel === 'MEDIUM') return 'text-orange-600 bg-orange-50 border-orange-200'
  return 'text-green-600 bg-green-50 border-green-200'
}

export default function DdlEventsPage() {
  const { dsId: dsIdStr } = useParams<{ dsId: string }>()
  const dsId = Number(dsIdStr)

  const { data: events = [], isLoading, error } = useQuery({
    queryKey: ['admin-ddl-events', dsId],
    queryFn: () => getDdlEvents(dsId, true),
    enabled: !!dsId,
  })

  return (
    <div className="p-6">
      <div className="mb-6">
        <Link to="/admin/datasources" className="flex items-center gap-1 text-xs text-gray-400 hover:text-gray-600 mb-1">
          <ArrowLeft size={12} /> 데이터소스로 돌아가기
        </Link>
        <h1 className="text-xl font-semibold text-gray-900">DDL 이벤트</h1>
        <p className="text-sm text-gray-500 mt-0.5">MySQL/MariaDB 스키마 변경 이력입니다.</p>
      </div>

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      {events.length === 0 && !isLoading && (
        <div className="bg-white border border-gray-200 rounded-xl p-12 text-center">
          <CheckCircle size={36} className="mx-auto text-green-400 mb-3" />
          <p className="text-gray-500 text-sm">DDL 이벤트가 없습니다.</p>
        </div>
      )}

      <div className="space-y-3">
        {events.map((ev: DdlEvent) => (
          <div key={ev.id} className="bg-white border border-gray-200 rounded-xl p-5">
            <div className="flex items-start gap-3">
              <Database size={16} className="mt-0.5 text-gray-400 flex-shrink-0" />
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1.5 flex-wrap">
                  <span className="font-mono text-sm font-semibold text-gray-900">
                    {ev.tableName}
                  </span>
                  {ev.eventType && (
                    <span className="text-xs bg-blue-100 text-blue-700 px-2 py-0.5 rounded">
                      {ev.eventType}
                    </span>
                  )}
                  {ev.riskLevel && (
                    <span className={`text-xs px-2 py-0.5 rounded font-medium border ${riskBadge(ev.riskLevel)}`}>
                      {ev.riskLevel}
                    </span>
                  )}
                </div>
                <p className="font-mono text-xs text-gray-600 bg-gray-50 rounded px-3 py-2 break-all">
                  {ev.sqlQuery}
                </p>
                <p className="text-xs text-gray-400 mt-2">
                  {new Date(ev.createdAt).toLocaleString()}
                </p>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
