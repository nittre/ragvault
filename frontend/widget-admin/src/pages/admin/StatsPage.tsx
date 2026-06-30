import { useQuery } from '@tanstack/react-query'
import { getStats } from '../../api/admin/conversations'
import type { DailyCount } from '../../api/admin/conversations'

interface StatCardProps {
  label: string
  value: string | number
  sub?: string
}

function StatCard({ label, value, sub }: StatCardProps) {
  return (
    <div className="bg-white border border-gray-200 rounded-xl px-5 py-4">
      <p className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-1">{label}</p>
      <p className="text-2xl font-semibold text-gray-900">{value}</p>
      {sub && <p className="text-xs text-gray-400 mt-0.5">{sub}</p>}
    </div>
  )
}

function BarChart({ data }: { data: DailyCount[] }) {
  if (!data.length) return null

  const maxCount = Math.max(...data.map(d => d.count), 1)

  return (
    <div className="bg-white border border-gray-200 rounded-xl px-6 py-5">
      <p className="text-sm font-medium text-gray-700 mb-4">일별 질의 추이 (최근 30일)</p>
      <div className="flex items-end gap-1 h-40">
        {data.map((d, i) => {
          const heightPct = Math.round((d.count / maxCount) * 100)
          const showLabel = i % 7 === 0 || i === data.length - 1
          const labelDate = d.day.slice(5) // "MM-DD"
          return (
            <div key={d.day} className="flex flex-col items-center flex-1 group relative">
              {/* Tooltip */}
              <div className="absolute bottom-full mb-1 left-1/2 -translate-x-1/2 bg-gray-800 text-white text-xs rounded px-1.5 py-0.5 whitespace-nowrap opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none z-10">
                {d.day}: {d.count}건
              </div>
              {/* Bar */}
              <div
                className="w-full bg-blue-500 rounded-t-sm group-hover:bg-blue-600 transition-colors"
                style={{ height: `${heightPct}%`, minHeight: d.count > 0 ? '2px' : '0' }}
              />
              {/* Label */}
              <div className="mt-1 text-gray-400 overflow-hidden" style={{ fontSize: '10px', height: '14px' }}>
                {showLabel ? labelDate : ''}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

export default function StatsPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['admin-stats'],
    queryFn: getStats,
  })

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-xl font-semibold text-gray-900">사용량 통계</h1>
      </div>

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      {data && (
        <>
          {/* 통계 카드 4개 */}
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
            <StatCard
              label="전체 질의"
              value={data.totalCount.toLocaleString()}
              sub="누적"
            />
            <StatCard
              label="최근 7일"
              value={data.last7dCount.toLocaleString()}
              sub="건"
            />
            <StatCard
              label="최근 30일 문서 매칭율"
              value={`${data.contextHitRate30d.toFixed(1)}%`}
              sub="최근 30일 기준"
            />
            <StatCard
              label="최근 30일 차단율"
              value={`${data.blockedRate30d.toFixed(1)}%`}
              sub="최근 30일 기준"
            />
          </div>

          {/* 바 차트 */}
          <BarChart data={data.daily30d} />
        </>
      )}
    </div>
  )
}
