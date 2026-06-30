import { useState } from 'react'
import { NavLink, Outlet, Link, useMatch } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  Users,
  Database,
  Table2,
  Shield,
  Settings,
  AlertTriangle,
  FileText,
  BarChart2,
  Key,
  Sliders,
  Server,
  BookOpen,
  ChevronDown,
  ChevronRight,
} from 'lucide-react'
import { useAuthStore } from '../../stores/authStore'
import { getDataSources } from '../../api/admin/dataSources'

const GLOBAL_NAV = [
  { to: '/admin/users', label: '사용자 관리', icon: Users },
  { to: '/admin/audit-logs', label: '감사 로그', icon: FileText },
  { to: '/admin/sql-logs', label: 'SQL 실행 로그', icon: Table2 },
  { to: '/admin/usage-stats', label: '사용량 통계', icon: BarChart2 },
  { to: '/admin/api-keys', label: 'API 키', icon: Key },
  { to: '/admin/param-limits', label: '파라미터 한도', icon: Sliders },
]

const DS_SUB_ITEMS = [
  { path: 'rag-tables', label: 'RAG 테이블', icon: Database },
  { path: 'sql-tables', label: 'SQL 테이블', icon: Table2 },
  { path: 'knowledge', label: '백과사전', icon: BookOpen },
  { path: 'masking-rules', label: 'PII 마스킹', icon: Shield },
  { path: 'ddl-events', label: 'DDL 이벤트', icon: AlertTriangle },
  { path: 'search-config', label: '검색 설정', icon: Settings },
]

const navCls = (isActive: boolean) =>
  `flex items-center gap-2.5 px-3 py-2 rounded-md text-sm font-medium transition-colors ${
    isActive
      ? 'bg-blue-50 text-blue-700'
      : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
  }`

export default function AdminLayout() {
  const email = useAuthStore(s => s.email)
  const role = useAuthStore(s => s.role)

  const match = useMatch('/admin/ds/:dsId/*')
  const activeDsId = match?.params?.dsId ? Number(match.params.dsId) : null

  const [expandedDsId, setExpandedDsId] = useState<number | null>(null)

  const { data: sources = [] } = useQuery({
    queryKey: ['admin-datasources'],
    queryFn: getDataSources,
  })

  const isExpanded = (id: number) => id === activeDsId || id === expandedDsId

  const toggleExpand = (id: number) => {
    setExpandedDsId(prev => (prev === id ? null : id))
  }

  return (
    <div className="flex h-screen bg-gray-50">
      <aside className="w-60 bg-white border-r border-gray-200 flex flex-col shrink-0">
        <div className="px-6 py-5 border-b border-gray-200">
          <span className="text-lg font-bold text-gray-900">RagVault Admin</span>
        </div>

        <nav className="flex-1 overflow-y-auto px-3 py-4">
          {/* 전역 */}
          <p className="px-3 mb-1 text-xs font-semibold text-gray-400 uppercase tracking-wider">
            전역
          </p>
          <div className="space-y-0.5 mb-4">
            {GLOBAL_NAV.map(({ to, label, icon: Icon }) => (
              <NavLink key={to} to={to} className={({ isActive }) => navCls(isActive)}>
                <Icon size={15} />
                {label}
              </NavLink>
            ))}
          </div>

          {/* 데이터소스 */}
          <p className="px-3 mb-1 text-xs font-semibold text-gray-400 uppercase tracking-wider">
            데이터소스
          </p>
          <div className="space-y-0.5">
            <NavLink to="/admin/ds" end className={({ isActive }) => navCls(isActive)}>
              <Server size={15} />
              DS 관리
            </NavLink>

            {sources.map(ds => (
              <div key={ds.id}>
                <div className="flex items-center rounded-md overflow-hidden">
                  <NavLink
                    to={`/admin/ds/${ds.id}/rag-tables`}
                    className={() =>
                      `flex-1 flex items-center gap-2.5 px-3 py-2 text-sm font-medium transition-colors truncate min-w-0 ${
                        activeDsId === ds.id
                          ? 'bg-blue-50 text-blue-700'
                          : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
                      }`
                    }
                  >
                    <Server size={14} className="shrink-0" />
                    <span className="truncate">{ds.name}</span>
                  </NavLink>
                  <button
                    onClick={() => toggleExpand(ds.id)}
                    className={`px-2 py-2 text-gray-400 hover:text-gray-600 transition-colors shrink-0 ${
                      activeDsId === ds.id ? 'bg-blue-50 hover:bg-blue-100' : 'hover:bg-gray-100'
                    }`}
                  >
                    {isExpanded(ds.id) ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
                  </button>
                </div>

                {isExpanded(ds.id) && (
                  <div className="ml-4 pl-3 border-l border-gray-200 mt-0.5 mb-1 space-y-0.5">
                    {DS_SUB_ITEMS.map(({ path, label, icon: Icon }) => (
                      <NavLink
                        key={path}
                        to={`/admin/ds/${ds.id}/${path}`}
                        className={({ isActive }) =>
                          `flex items-center gap-2 px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${
                            isActive
                              ? 'bg-blue-50 text-blue-700'
                              : 'text-gray-500 hover:bg-gray-100 hover:text-gray-800'
                          }`
                        }
                      >
                        <Icon size={13} />
                        {label}
                      </NavLink>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        </nav>

        <div className="px-4 py-4 border-t border-gray-200 space-y-2">
          {email && (
            <div className="text-xs text-gray-500 truncate">
              <div className="font-medium text-gray-700 truncate">{email}</div>
              <div>{role}</div>
            </div>
          )}
          <Link to="/" className="block text-xs text-blue-600 hover:text-blue-800">
            ← 채팅으로
          </Link>
        </div>
      </aside>

      <main className="flex-1 overflow-y-auto">
        <Outlet />
      </main>
    </div>
  )
}
