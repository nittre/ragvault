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

  const routing = data?.routing30d
  const barItems = routing && [
    { label: 'RAG', value: routing.RAG, color: 'bg-blue-500' },
    { label: 'SQL', value: routing.SQL, color: 'bg-green-500' },
    { label: 'HYBRID', value: routing.HYBRID, color: 'bg-amber-500' },
    { label: '차단', value: routing.REJECT, color: 'bg-red-500' },
    { label: '기타', value: routing.OTHER, color: 'bg-gray-400' },
  ]
  const routingTotal = barItems ? barItems.reduce((sum, b) => sum + b.value, 0) : 0
  const maxBar = barItems ? Math.max(...barItems.map(b => b.value), 1) : 1

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
              label="전체"
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
              value={`${(data.contextHitRate30d * 100).toFixed(1)}%`}
              sub="최근 30일 기준"
            />
            <StatCard
              label="최근 30일 차단율"
              value={`${(data.blockedRate30d * 100).toFixed(1)}%`}
              sub="최근 30일 기준"
            />
          </div>

          {/* 바 차트 */}
          <div className="mb-6">
            <BarChart data={data.daily30d} />
          </div>

          {/* 라우팅 상세 */}
          {barItems && (
            <div>
              <h2 className="text-base font-semibold text-gray-900 mb-1">라우팅 상세</h2>
              <p className="text-sm text-gray-500 mb-4">최근 30일 요청 수 및 경로별 분포입니다.</p>

              <div className="bg-white border border-gray-200 rounded-xl p-5 mb-6">
                <h3 className="text-sm font-medium text-gray-700 mb-4">경로별 분포</h3>
                <div className="space-y-3">
                  {barItems.map(({ label, value, color }) => (
                    <div key={label} className="flex items-center gap-3">
                      <span className="text-xs text-gray-500 w-14 text-right flex-shrink-0">{label}</span>
                      <div className="flex-1 bg-gray-100 rounded-full h-5 overflow-hidden">
                        <div
                          className={`${color} h-full rounded-full transition-all`}
                          style={{ width: `${(value / maxBar) * 100}%` }}
                        />
                      </div>
                      <span className="text-xs font-medium text-gray-700 w-10 flex-shrink-0">
                        {value.toLocaleString()}
                      </span>
                    </div>
                  ))}
                </div>
              </div>

              <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
                <table className="w-full text-sm">
                  <thead className="bg-gray-50 border-b border-gray-200">
                    <tr>
                      {['경로', '요청 수', '비율'].map(h => (
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
                    {barItems.map(({ label, value, color }) => (
                      <tr key={label} className="hover:bg-gray-50">
                        <td className="px-4 py-3">
                          <span className="flex items-center gap-2">
                            <span className={`inline-block w-2.5 h-2.5 rounded-full ${color}`} />
                            {label}
                          </span>
                        </td>
                        <td className="px-4 py-3 font-medium text-gray-900">{value.toLocaleString()}</td>
                        <td className="px-4 py-3 text-gray-500">
                          {routingTotal > 0 ? `${((value / routingTotal) * 100).toFixed(1)}%` : '-'}
                        </td>
                      </tr>
                    ))}
                    <tr className="bg-gray-50 font-medium">
                      <td className="px-4 py-3 text-gray-700">합계</td>
                      <td className="px-4 py-3 text-gray-900">{routingTotal.toLocaleString()}</td>
                      <td className="px-4 py-3 text-gray-500">100%</td>
                    </tr>
                  </tbody>
                </table>
              </div>

              {/* 실행 통계 */}
              <div className="mt-6">
                <h3 className="text-sm font-medium text-gray-700 mb-1">실행 통계</h3>
                <p className="text-xs text-gray-400 mb-3">
                  HYBRID 요청 내부에서 실행된 것까지 포함된 실제 실행 횟수입니다. 위 라우팅 분류와 합계가 다를 수 있습니다.
                </p>
                <div className="bg-green-50 border border-gray-200 rounded-xl p-4 max-w-xs">
                  <p className="text-xs text-gray-500 mb-1">SQL 실행 횟수</p>
                  <p className="text-2xl font-bold text-green-600">
                    {data.executions.sqlQuery.toLocaleString()}
                  </p>
                </div>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  )
}
