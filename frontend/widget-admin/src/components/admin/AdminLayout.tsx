import { useState } from 'react'
import { NavLink, Outlet, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  Users,
  BookOpen,
  BarChart2,
  Shield,
  FileText,
  Search,
  LayoutDashboard,
  LogOut,
  Database,
  Terminal,
  ChevronDown,
  ChevronRight,
  Table,
  Code2,
  KeyRound,
} from 'lucide-react'
import { useAuthStore } from '../../stores/authStore'
import { logout } from '../../api/auth'
import { listDataSources } from '../../api/admin/datasources'

interface NavItem {
  to: string
  label: string
  icon: React.ElementType
  active: boolean
  soon?: boolean
}

const NAV_ITEMS: NavItem[] = [
  { to: '/admin/users', label: '사용자 관리', icon: Users, active: true },
  { to: '/admin/knowledge', label: '지식문서', icon: BookOpen, active: true },
  { to: '/admin/conversations', label: '대화 로그', icon: FileText, active: true },
  { to: '/admin/stats', label: '사용량 통계', icon: BarChart2, active: true },
  { to: '/admin/masking', label: 'PII 마스킹', icon: Shield, active: true },
  { to: '/admin/audit', label: '감사 로그', icon: FileText, active: true },
  { to: '/admin/search', label: '검색 설정', icon: Search, active: true },
  { to: '/admin/widget', label: '위젯 설정', icon: LayoutDashboard, active: true },
  { to: '/admin/query-console', label: '쿼리 콘솔', icon: Terminal, active: true },
]

const activeCls =
  'flex items-center gap-2.5 px-3 py-2 rounded-md text-sm font-medium bg-blue-50 text-blue-700'
const inactiveCls =
  'flex items-center gap-2.5 px-3 py-2 rounded-md text-sm font-medium text-gray-600 hover:bg-gray-100 hover:text-gray-900 transition-colors'
const disabledCls =
  'flex items-center gap-2.5 px-3 py-2 rounded-md text-sm font-medium text-gray-300 cursor-not-allowed select-none'
const subActiveCls =
  'flex items-center gap-2 pl-8 pr-3 py-1.5 rounded-md text-xs font-medium text-blue-700 bg-blue-50'
const subInactiveCls =
  'flex items-center gap-2 pl-8 pr-3 py-1.5 rounded-md text-xs font-medium text-gray-500 hover:bg-gray-100 hover:text-gray-800 transition-colors'

export default function AdminLayout() {
  const email = useAuthStore(s => s.email)
  const role = useAuthStore(s => s.role)
  const clearAuth = useAuthStore(s => s.clearAuth)
  const params = useParams<{ dsId?: string }>()
  const currentDsId = params.dsId ? Number(params.dsId) : null

  const [expandedDsId, setExpandedDsId] = useState<number | null>(currentDsId)

  const { data: datasources = [] } = useQuery({
    queryKey: ['admin-datasources'],
    queryFn: listDataSources,
    staleTime: 30_000,
  })

  const handleLogout = async () => {
    try {
      await logout()
    } finally {
      clearAuth()
      window.location.href = '/login'
    }
  }

  const toggleDs = (id: number) => {
    setExpandedDsId(prev => (prev === id ? null : id))
  }

  return (
    <div className="flex h-screen bg-gray-50">
      <aside className="w-60 bg-white border-r border-gray-200 flex flex-col shrink-0">
        {/* Logo */}
        <div className="px-6 py-5 border-b border-gray-200">
          <span className="text-lg font-bold text-gray-900">RagVault</span>
          <span className="ml-1.5 text-xs font-medium text-blue-600 bg-blue-50 px-1.5 py-0.5 rounded">
            Widget Admin
          </span>
        </div>

        {/* Nav */}
        <nav className="flex-1 overflow-y-auto px-3 py-4 space-y-4">
          {/* 정적 메뉴 */}
          <div>
            <p className="px-3 mb-2 text-xs font-semibold text-gray-400 uppercase tracking-wider">
              관리
            </p>
            <div className="space-y-0.5">
              {NAV_ITEMS.map(({ to, label, icon: Icon, active, soon }) => {
                if (!active) {
                  return (
                    <div key={to} className={disabledCls} title="준비 중">
                      <Icon size={15} />
                      <span>{label}</span>
                      {soon && (
                        <span className="ml-auto text-xs text-gray-300 font-normal">준비중</span>
                      )}
                    </div>
                  )
                }
                return (
                  <NavLink
                    key={to}
                    to={to}
                    className={({ isActive }) => (isActive ? activeCls : inactiveCls)}
                  >
                    <Icon size={15} />
                    {label}
                  </NavLink>
                )
              })}
            </div>
          </div>

          {/* 데이터소스 섹션 */}
          <div>
            <p className="px-3 mb-2 text-xs font-semibold text-gray-400 uppercase tracking-wider">
              데이터소스
            </p>

            <div className="space-y-0.5">
              <NavLink
                to="/admin/datasources"
                end
                className={({ isActive }) => (isActive ? activeCls : inactiveCls)}
              >
                <Database size={15} />
                DS 관리
              </NavLink>

              {datasources.length === 0 && (
                <p className="px-3 py-2 text-xs text-gray-400">등록된 데이터소스 없음</p>
              )}
              {datasources.map(ds => {
                const isExpanded = expandedDsId === ds.id
                return (
                  <div key={ds.id}>
                    <button
                      onClick={() => toggleDs(ds.id)}
                      className="w-full flex items-center gap-2.5 px-3 py-2 rounded-md text-sm font-medium text-gray-600 hover:bg-gray-100 hover:text-gray-900 transition-colors"
                    >
                      <Database size={15} className="shrink-0" />
                      <span className="flex-1 truncate text-left">{ds.name}</span>
                      {isExpanded ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
                    </button>

                    {isExpanded && (
                      <div className="mt-0.5 space-y-0.5">
                        <NavLink
                          to={`/admin/datasources/${ds.id}/rag-tables`}
                          className={({ isActive }) => (isActive ? subActiveCls : subInactiveCls)}
                        >
                          <Table size={13} />
                          RAG 테이블
                        </NavLink>
                        <NavLink
                          to={`/admin/datasources/${ds.id}/sql-tables`}
                          className={({ isActive }) => (isActive ? subActiveCls : subInactiveCls)}
                        >
                          <Code2 size={13} />
                          SQL 테이블
                        </NavLink>
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          </div>
        </nav>

        {/* Footer */}
        <div className="px-4 py-4 border-t border-gray-200 space-y-3">
          {email && (
            <div className="text-xs text-gray-500 truncate">
              <div className="font-medium text-gray-700 truncate">{email}</div>
              <div className="text-gray-400">{role}</div>
            </div>
          )}
          <NavLink
            to="/admin/settings"
            className={({ isActive }) =>
              `flex items-center gap-2 text-xs transition-colors w-full ${
                isActive ? 'text-blue-600' : 'text-gray-500 hover:text-gray-700'
              }`
            }
          >
            <KeyRound size={13} />
            비밀번호 변경
          </NavLink>
          <button
            onClick={handleLogout}
            className="flex items-center gap-2 text-xs text-gray-500 hover:text-red-600 transition-colors w-full"
          >
            <LogOut size={13} />
            로그아웃
          </button>
        </div>
      </aside>

      <main className="flex-1 overflow-y-auto">
        <Outlet />
      </main>
    </div>
  )
}
