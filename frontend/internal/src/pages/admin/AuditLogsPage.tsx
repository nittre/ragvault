import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { getAuditLogs } from '../../api/admin/auditLogs'

const PAGE_SIZE = 50

export default function AuditLogsPage() {
  const [page, setPage] = useState(0)

  const { data, isLoading, error } = useQuery({
    queryKey: ['admin-audit-logs', page],
    queryFn: () => getAuditLogs(page, PAGE_SIZE),
  })

  const logs = data?.data ?? []
  const total = data?.total ?? 0
  const hasNext = (page + 1) * PAGE_SIZE < total

  return (
    <div className="p-6">
      <div className="mb-6">
        <h1 className="text-xl font-semibold text-gray-900">감사 로그</h1>
        <p className="text-sm text-gray-500 mt-0.5">관리자 및 시스템 작업 이력입니다.</p>
      </div>

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              {['시각', '행위자', '액션', '대상 유형', '대상 ID', '상세'].map(h => (
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
            {logs.map(log => (
              <tr key={log.id} className="hover:bg-gray-50">
                <td className="px-4 py-3 text-gray-400 text-xs whitespace-nowrap">
                  {new Date(log.createdAt).toLocaleString()}
                </td>
                <td className="px-4 py-3 text-gray-700 text-xs">{log.actor}</td>
                <td className="px-4 py-3">
                  <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-blue-50 text-blue-700">
                    {log.action}
                  </span>
                </td>
                <td className="px-4 py-3 text-gray-600 text-xs">{log.targetType}</td>
                <td className="px-4 py-3 font-mono text-gray-500 text-xs max-w-[120px] truncate">
                  {log.targetId}
                </td>
                <td className="px-4 py-3 text-gray-500 text-xs max-w-xs truncate">{log.detail}</td>
              </tr>
            ))}
            {logs.length === 0 && !isLoading && (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-gray-400 text-sm">
                  감사 로그가 없습니다.
                </td>
              </tr>
            )}
          </tbody>
        </table>

        <div className="flex items-center justify-between px-4 py-3 border-t border-gray-200 bg-gray-50">
          <span className="text-xs text-gray-500">
            페이지 {page + 1} · 전체 {total.toLocaleString()}건
          </span>
          <div className="flex gap-1">
            <button
              onClick={() => setPage(p => Math.max(0, p - 1))}
              disabled={page === 0}
              className="p-1.5 rounded hover:bg-gray-200 disabled:opacity-30 text-gray-600"
            >
              <ChevronLeft size={16} />
            </button>
            <button
              onClick={() => setPage(p => p + 1)}
              disabled={!hasNext}
              className="p-1.5 rounded hover:bg-gray-200 disabled:opacity-30 text-gray-600"
            >
              <ChevronRight size={16} />
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
