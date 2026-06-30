import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getConversations } from '../../api/admin/conversations'
import type { ConversationLogDto } from '../../api/admin/conversations'

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function truncate(str: string, max: number): string {
  return str.length > max ? str.slice(0, max) + '…' : str
}

function ContextBadge({ hasContext }: { hasContext: boolean }) {
  return (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${
        hasContext ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
      }`}
    >
      {hasContext ? '✓' : '✗'}
    </span>
  )
}

function BlockedBadge({ isBlocked }: { isBlocked: boolean }) {
  return isBlocked ? (
    <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-red-100 text-red-600">
      차단
    </span>
  ) : null
}

interface DetailModalProps {
  row: ConversationLogDto
  onClose: () => void
}

function DetailModal({ row, onClose }: DetailModalProps) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-2xl mx-4 flex flex-col max-h-[90vh]">
        <div className="px-6 py-4 border-b border-gray-200 flex items-center justify-between shrink-0">
          <div>
            <h2 className="text-base font-semibold text-gray-900">대화 상세</h2>
            <p className="text-xs text-gray-400 mt-0.5">{formatDate(row.createdAt)} · {row.siteKey}</p>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 transition-colors text-lg leading-none"
          >
            ×
          </button>
        </div>
        <div className="px-6 py-4 flex flex-col gap-4 overflow-y-auto flex-1">
          <div>
            <p className="text-xs font-medium text-gray-500 mb-1.5 uppercase tracking-wide">사용자 질문</p>
            <div className="bg-gray-50 border border-gray-200 rounded-lg px-4 py-3 text-sm text-gray-800 whitespace-pre-wrap leading-relaxed">
              {row.userMessage}
            </div>
          </div>
          <div>
            <p className="text-xs font-medium text-gray-500 mb-1.5 uppercase tracking-wide">봇 응답</p>
            <div className="bg-gray-50 border border-gray-200 rounded-lg px-4 py-3 text-sm text-gray-800 whitespace-pre-wrap leading-relaxed">
              {row.botResponse}
            </div>
          </div>
          <div className="flex items-center gap-4 text-xs text-gray-500">
            <span>문서 매칭: <ContextBadge hasContext={row.hasContext} /></span>
            <span>소스 수: <span className="font-medium text-gray-700">{row.sourceCount}</span></span>
            {row.isBlocked && <BlockedBadge isBlocked={row.isBlocked} />}
          </div>
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

export default function ConversationsPage() {
  const [page, setPage] = useState(0)
  const [siteKeyFilter, setSiteKeyFilter] = useState('')
  const [selectedRow, setSelectedRow] = useState<ConversationLogDto | null>(null)

  const { data, isLoading, error } = useQuery({
    queryKey: ['admin-conversations', page, siteKeyFilter],
    queryFn: () =>
      getConversations({
        page,
        size: 20,
        siteKey: siteKeyFilter || undefined,
      }),
  })

  const totalPages = data?.totalPages ?? 0
  const totalElements = data?.totalElements ?? 0
  const rows = data?.content ?? []

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-xl font-semibold text-gray-900">대화 로그</h1>
        {!isLoading && (
          <span className="text-sm text-gray-500">
            총 <span className="font-medium text-gray-800">{totalElements.toLocaleString()}</span>건
          </span>
        )}
      </div>

      {/* 필터 */}
      <div className="mb-4">
        <input
          type="text"
          value={siteKeyFilter}
          onChange={e => {
            setSiteKeyFilter(e.target.value)
            setPage(0)
          }}
          placeholder="사이트키로 필터…"
          className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-56 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
      </div>

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      {!isLoading && (
        <>
          <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {['시간', '사이트키', '사용자 질문', '봇 응답', '문서매칭', '차단'].map(h => (
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
                    <td className="px-4 py-3 text-gray-600 text-xs font-mono">
                      {row.siteKey}
                    </td>
                    <td className="px-4 py-3 text-gray-700 max-w-xs">
                      <span className="block truncate">{truncate(row.userMessage, 100)}</span>
                    </td>
                    <td className="px-4 py-3 text-gray-500 max-w-xs">
                      <span className="block truncate">{truncate(row.botResponse, 100)}</span>
                    </td>
                    <td className="px-4 py-3">
                      <ContextBadge hasContext={row.hasContext} />
                    </td>
                    <td className="px-4 py-3">
                      <BlockedBadge isBlocked={row.isBlocked} />
                    </td>
                  </tr>
                ))}
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={6} className="px-4 py-8 text-center text-gray-400 text-sm">
                      대화 로그가 없습니다.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          {/* 페이지네이션 */}
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
