import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { getDailyUsageStat } from '../../api/admin/usageStats'

function toDateString(d: Date) {
  return d.toISOString().slice(0, 10)
}

export default function UsageStatsPage() {
  const [date, setDate] = useState(toDateString(new Date()))

  const { data, isLoading, error } = useQuery({
    queryKey: ['admin-usage-stats', date],
    queryFn: () => getDailyUsageStat(date),
  })

  const prevDay = () => {
    const d = new Date(date)
    d.setDate(d.getDate() - 1)
    setDate(toDateString(d))
  }
  const nextDay = () => {
    const d = new Date(date)
    d.setDate(d.getDate() + 1)
    setDate(toDateString(d))
  }
  const isToday = date === toDateString(new Date())

  const total = data?.totalQueries ?? 0
  const rag = data?.breakdown?.RAG ?? 0
  const sql = data?.breakdown?.SQL_QUERY ?? 0
  const file = data?.breakdown?.FILE_UPLOAD ?? 0
  const other = total - rag - sql - file

  const cards = [
    { label: '전체 요청', value: total, color: 'text-gray-900', bg: 'bg-white' },
    { label: 'RAG 채팅', value: rag, color: 'text-blue-600', bg: 'bg-blue-50' },
    { label: 'SQL 조회', value: sql, color: 'text-green-600', bg: 'bg-green-50' },
    { label: '파일 업로드', value: file, color: 'text-purple-600', bg: 'bg-purple-50' },
  ]

  const barItems = [
    { label: 'RAG', value: rag, color: 'bg-blue-500' },
    { label: 'SQL', value: sql, color: 'bg-green-500' },
    { label: '파일 업로드', value: file, color: 'bg-purple-500' },
    { label: '기타', value: other > 0 ? other : 0, color: 'bg-gray-400' },
  ]
  const maxBar = Math.max(...barItems.map(b => b.value), 1)

  return (
    <div className="p-6">
      {/* 헤더 + 날짜 선택 */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">사용량 통계</h1>
          <p className="text-sm text-gray-500 mt-0.5">선택한 날짜의 요청 수 및 경로별 분포입니다.</p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={prevDay}
            className="p-1.5 rounded-lg border border-gray-300 hover:bg-gray-50 text-gray-600"
          >
            <ChevronLeft size={16} />
          </button>
          <input
            type="date"
            value={date}
            max={toDateString(new Date())}
            onChange={e => setDate(e.target.value)}
            className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <button
            onClick={nextDay}
            disabled={isToday}
            className="p-1.5 rounded-lg border border-gray-300 hover:bg-gray-50 text-gray-600 disabled:opacity-30"
          >
            <ChevronRight size={16} />
          </button>
        </div>
      </div>

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      {/* 요약 카드 */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
        {cards.map(({ label, value, color, bg }) => (
          <div key={label} className={`${bg} border border-gray-200 rounded-xl p-4`}>
            <p className="text-xs text-gray-500 mb-1">{label}</p>
            <p className={`text-2xl font-bold ${color}`}>{value.toLocaleString()}</p>
          </div>
        ))}
      </div>

      {/* 가로 바 차트 */}
      {data && (
        <div className="bg-white border border-gray-200 rounded-xl p-5 mb-6">
          <h2 className="text-sm font-medium text-gray-700 mb-4">경로별 분포</h2>
          <div className="space-y-3">
            {barItems.map(({ label, value, color }) => (
              <div key={label} className="flex items-center gap-3">
                <span className="text-xs text-gray-500 w-20 text-right flex-shrink-0">{label}</span>
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
      )}

      {/* 상세 테이블 */}
      {data && (
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
                    {total > 0 ? `${((value / total) * 100).toFixed(1)}%` : '-'}
                  </td>
                </tr>
              ))}
              <tr className="bg-gray-50 font-medium">
                <td className="px-4 py-3 text-gray-700">합계</td>
                <td className="px-4 py-3 text-gray-900">{total.toLocaleString()}</td>
                <td className="px-4 py-3 text-gray-500">100%</td>
              </tr>
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
