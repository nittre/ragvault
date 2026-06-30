import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { getSqlLogs, type SqlLog } from '../../api/admin/sqlLogs'

const PAGE_SIZE = 20

const CATEGORY_LABEL: Record<string, { label: string; cls: string }> = {
  HALLUCINATED_COLUMN: { label: '환각 컬럼',     cls: 'bg-purple-50 text-purple-700' },
  NULL_ARITHMETIC:     { label: 'NULL/0 나눗셈', cls: 'bg-orange-50 text-orange-700' },
  FULL_SCAN:           { label: '풀스캔',         cls: 'bg-yellow-50 text-yellow-700' },
  SYNTAX_ERROR:        { label: '구문 오류',      cls: 'bg-red-50 text-red-700' },
  AGGREGATE_ERROR:     { label: '집계 오류',      cls: 'bg-blue-50 text-blue-700' },
  OTHER:               { label: '기타',           cls: 'bg-gray-100 text-gray-600' },
}

const FILTERS = [
  { value: '',                    label: '전체' },
  { value: 'HALLUCINATED_COLUMN', label: '환각 컬럼' },
  { value: 'NULL_ARITHMETIC',     label: 'NULL/0 나눗셈' },
  { value: 'FULL_SCAN',           label: '풀스캔' },
  { value: 'SYNTAX_ERROR',        label: '구문 오류' },
  { value: 'AGGREGATE_ERROR',     label: '집계 오류' },
  { value: 'OTHER',               label: '기타' },
]

function validationBadge(val: string | null) {
  if (val === 'denied')
    return <span className="inline-flex px-2 py-0.5 rounded text-xs font-medium bg-red-50 text-red-700">거부</span>
  if (val === 'allowed')
    return <span className="inline-flex px-2 py-0.5 rounded text-xs font-medium bg-green-50 text-green-700">허용</span>
  return <span className="text-gray-400 text-xs">—</span>
}

function statusBadge(status: string | null) {
  if (status === 'success')
    return <span className="inline-flex px-2 py-0.5 rounded text-xs font-medium bg-green-50 text-green-700">성공</span>
  if (status === 'error')
    return <span className="inline-flex px-2 py-0.5 rounded text-xs font-medium bg-orange-50 text-orange-700">오류</span>
  if (status === 'timeout')
    return <span className="inline-flex px-2 py-0.5 rounded text-xs font-medium bg-yellow-50 text-yellow-700">타임아웃</span>
  return <span className="text-gray-400 text-xs">—</span>
}

function categoryBadge(cat: string | null) {
  if (!cat) return <span className="text-gray-400 text-xs">—</span>
  const { label, cls } = CATEGORY_LABEL[cat] ?? { label: cat, cls: 'bg-gray-100 text-gray-600' }
  return <span className={`inline-flex px-2 py-0.5 rounded text-xs font-medium ${cls}`}>{label}</span>
}

function ExpandableCell({ text }: { text: string | null }) {
  const [open, setOpen] = useState(false)
  if (!text) return <span className="text-gray-400">—</span>
  return (
    <div className="max-w-[200px]">
      <p
        className={`text-xs text-gray-600 font-mono cursor-pointer ${open ? 'whitespace-pre-wrap' : 'truncate'}`}
        onClick={() => setOpen(o => !o)}
        title={open ? '접기' : '펼치기'}
      >
        {text}
      </p>
    </div>
  )
}

export default function SqlLogsPage() {
  const [category, setCategory] = useState('')
  const [page, setPage] = useState(0)

  const queryParams = category ? { failureCategory: category } : {}

  const { data = [], isLoading, error } = useQuery({
    queryKey: ['admin-sql-logs', category],
    queryFn: () => getSqlLogs({ ...queryParams, limit: 200 }),
  })

  const total = data.length
  const paged = data.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)
  const hasNext = (page + 1) * PAGE_SIZE < total

  const handleFilter = (val: string) => {
    setCategory(val)
    setPage(0)
  }

  return (
    <div className="p-6">
      <div className="mb-5">
        <h1 className="text-xl font-semibold text-gray-900">SQL 실행 로그</h1>
        <p className="text-sm text-gray-500 mt-0.5">LLM이 생성한 SQL의 검증·실행 이력입니다.</p>
      </div>

      {/* 카테고리 필터 */}
      <div className="flex flex-wrap gap-1 mb-4">
        {FILTERS.map(f => (
          <button
            key={f.value}
            onClick={() => handleFilter(f.value)}
            className={`px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
              category === f.value
                ? 'bg-blue-600 text-white'
                : 'bg-white border border-gray-200 text-gray-600 hover:bg-gray-50'
            }`}
          >
            {f.label}
          </button>
        ))}
      </div>

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              {['시각', '사용자', '질문', '생성 SQL', '카테고리', '검증', '실행', '소요(ms)', '사유 / 오류'].map(h => (
                <th
                  key={h}
                  className="px-3 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wide whitespace-nowrap"
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {paged.map((log: SqlLog) => (
              <tr key={log.id} className="hover:bg-gray-50">
                <td className="px-3 py-3 text-gray-400 text-xs whitespace-nowrap">
                  {new Date(log.createdAt).toLocaleString('ko-KR')}
                </td>
                <td className="px-3 py-3 text-xs text-gray-600 max-w-[100px] truncate">
                  {log.userEmail ?? '—'}
                </td>
                <td className="px-3 py-3 text-xs text-gray-700 max-w-[160px] truncate" title={log.question}>
                  {log.question}
                </td>
                <td className="px-3 py-3">
                  <ExpandableCell text={log.generatedSql} />
                </td>
                <td className="px-3 py-3 whitespace-nowrap">{categoryBadge(log.failureCategory)}</td>
                <td className="px-3 py-3 whitespace-nowrap">{validationBadge(log.validationResult)}</td>
                <td className="px-3 py-3 whitespace-nowrap">{statusBadge(log.executionStatus)}</td>
                <td className="px-3 py-3 text-xs text-gray-500 text-right whitespace-nowrap">
                  {log.elapsedMs != null ? log.elapsedMs.toLocaleString() : '—'}
                </td>
                <td
                  className="px-3 py-3 text-xs text-red-600 max-w-[180px] truncate"
                  title={log.validationReason ?? log.errorMessage ?? undefined}
                >
                  {log.validationReason ?? log.errorMessage ?? '—'}
                </td>
              </tr>
            ))}
            {paged.length === 0 && !isLoading && (
              <tr>
                <td colSpan={9} className="px-4 py-8 text-center text-gray-400 text-sm">
                  로그가 없습니다.
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
