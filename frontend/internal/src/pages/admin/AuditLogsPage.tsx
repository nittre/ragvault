import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getAuditLogs } from '../../api/admin/auditLogs'
import type { AuditLog } from '../../api/admin/auditLogs'

const PAGE_SIZE = 30

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function ActionBadge({ action }: { action: string }) {
  let cls = 'bg-gray-100 text-gray-600'
  if (action === 'LOGIN' || action === 'LOGOUT') {
    cls = 'bg-blue-100 text-blue-700'
  } else if (action.startsWith('KNOWLEDGE_')) {
    cls = 'bg-green-100 text-green-700'
  } else if (action.startsWith('USER_')) {
    cls = 'bg-purple-100 text-purple-700'
  } else if (action === 'CHAT' || action === 'SQL_QUERY' || action === 'FILE_UPLOAD') {
    cls = 'bg-amber-100 text-amber-700'
  }
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${cls}`}>
      {action}
    </span>
  )
}

interface DetailModalProps {
  row: AuditLog
  onClose: () => void
}

function DetailModal({ row, onClose }: DetailModalProps) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-lg mx-4 flex flex-col max-h-[90vh]">
        <div className="px-6 py-4 border-b border-gray-200 flex items-center justify-between shrink-0">
          <div>
            <h2 className="text-base font-semibold text-gray-900">감사 로그 상세</h2>
            <p className="text-xs text-gray-400 mt-0.5">{formatDate(row.createdAt)}</p>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 transition-colors text-lg leading-none"
          >
            ×
          </button>
        </div>
        <div className="px-6 py-4 flex flex-col gap-3 overflow-y-auto flex-1">
          <Row label="행위자" value={row.userEmail} />
          <Row label="액션">
            <ActionBadge action={row.action} />
          </Row>
          <Row label="대상 유형" value={row.intent || '-'} />
          <Row label="대상 ID" value={row.requestSummary || '-'} mono />
          <Row label="IP 주소" value={row.ipAddress || '-'} mono />
          {row.responseId && (
            <div>
              <p className="text-xs font-medium text-gray-500 mb-1.5 uppercase tracking-wide">상세</p>
              <div className="bg-gray-50 border border-gray-200 rounded-lg px-4 py-3 text-sm text-gray-800 whitespace-pre-wrap leading-relaxed font-mono text-xs">
                {row.responseId}
              </div>
            </div>
          )}
        </div>
        <div className="px-6 py-4 border-t border-gray-200 flex justify-end shrink-0">
          <button
            onClick={onClose}
            className="text-gray-500 hover:text-gray-700 px-4 py-1.5 text-sm transition-colors"
          >
            닫기
          </button>
        </div>
      </div>
    </div>
  )
}

interface RowProps {
  label: string
  value?: string
  mono?: boolean
  children?: React.ReactNode
}

function Row({ label, value, mono, children }: RowProps) {
  return (
    <div className="flex items-start gap-4">
      <span className="text-xs font-medium text-gray-500 uppercase tracking-wide w-20 shrink-0 pt-0.5">{label}</span>
      {children ?? (
        <span className={`text-sm text-gray-800 ${mono ? 'font-mono' : ''}`}>{value}</span>
      )}
    </div>
  )
}

export default function AuditLogsPage() {
  const [page, setPage] = useState(0)
  const [selectedRow, setSelectedRow] = useState<AuditLog | null>(null)

  const { data, isLoading, error } = useQuery({
    queryKey: ['admin-audit-logs', page],
    queryFn: () => getAuditLogs(page, PAGE_SIZE),
  })

  const rows = data?.data ?? []
  const total = data?.total ?? 0
  const totalPages = Math.ceil(total / PAGE_SIZE)

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">감사 로그</h1>
          <p className="text-sm text-gray-500 mt-0.5">관리자 및 시스템 작업 이력입니다.</p>
        </div>
        {!isLoading && (
          <span className="text-sm text-gray-500">
            총 <span className="font-medium text-gray-800">{total.toLocaleString()}</span>건
          </span>
        )}
      </div>

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      {!isLoading && (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {['시간', '행위자', '액션', '대상유형', '대상ID', 'IP', '상세'].map(h => (
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
                {rows.map(row => (
                  <tr
                    key={row.id}
                    className="hover:bg-gray-50 cursor-pointer"
                    onClick={() => setSelectedRow(row)}
                  >
                    <td className="px-4 py-3 text-gray-400 text-xs whitespace-nowrap">
                      {formatDate(row.createdAt)}
                    </td>
                    <td className="px-4 py-3 text-gray-700 text-xs">{row.userEmail}</td>
                    <td className="px-4 py-3">
                      <ActionBadge action={row.action} />
                    </td>
                    <td className="px-4 py-3 text-gray-500 text-xs">{row.intent || '-'}</td>
                    <td className="px-4 py-3 text-gray-500 text-xs font-mono">{row.requestSummary || '-'}</td>
                    <td className="px-4 py-3 text-gray-400 text-xs font-mono">{row.ipAddress || '-'}</td>
                    <td className="px-4 py-3 text-gray-400 text-xs max-w-xs">
                      <span className="block truncate">{row.responseId || '-'}</span>
                    </td>
                  </tr>
                ))}
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={7} className="px-4 py-8 text-center text-gray-400 text-sm">
                      감사 로그가 없습니다.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          {totalPages > 1 && (
            <div className="flex items-center justify-between mt-4">
              <button
                onClick={() => setPage(p => Math.max(0, p - 1))}
                disabled={page === 0}
                className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg text-gray-600 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
              >
                이전
              </button>
              <span className="text-sm text-gray-500">
                <span className="font-medium text-gray-800">{page + 1}</span> / {totalPages} 페이지
              </span>
              <button
                onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                disabled={page >= totalPages - 1}
                className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg text-gray-600 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
              >
                다음
              </button>
            </div>
          )}
        </>
      )}

      {selectedRow && (
        <DetailModal row={selectedRow} onClose={() => setSelectedRow(null)} />
      )}
    </div>
  )
}
