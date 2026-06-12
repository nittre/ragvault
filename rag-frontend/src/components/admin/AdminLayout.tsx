import { NavLink, Outlet, Link } from 'react-router-dom'
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
} from 'lucide-react'
import { useAuthStore } from '../../stores/authStore'

const navItems = [
  { to: '/admin/users', label: '사용자 관리', icon: Users },
  { to: '/admin/rag-tables', label: 'RAG 테이블', icon: Database },
  { to: '/admin/sql-tables', label: 'SQL 테이블', icon: Table2 },
  { to: '/admin/masking-rules', label: 'PII 마스킹', icon: Shield },
  { to: '/admin/search-config', label: '검색 설정', icon: Settings },
  { to: '/admin/ddl-events', label: 'DDL 이벤트', icon: AlertTriangle },
  { to: '/admin/audit-logs', label: '감사 로그', icon: FileText },
  { to: '/admin/usage-stats', label: '사용량 통계', icon: BarChart2 },
  { to: '/admin/api-keys', label: 'API 키', icon: Key },
  { to: '/admin/param-limits', label: '파라미터 한도', icon: Sliders },
]

export default function AdminLayout() {
  const email = useAuthStore(s => s.email)
  const role = useAuthStore(s => s.role)

  return (
    <div className="flex h-screen bg-gray-50">
      {/* Sidebar */}
      <aside className="w-64 bg-white border-r border-gray-200 flex flex-col">
        {/* Logo */}
        <div className="px-6 py-5 border-b border-gray-200">
          <span className="text-lg font-bold text-gray-900">RagVault Admin</span>
        </div>

        {/* Navigation */}
        <nav className="flex-1 overflow-y-auto px-3 py-4 space-y-1">
          {navItems.map(({ to, label, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              className={({ isActive }) =>
                `flex items-center gap-3 px-3 py-2 rounded-md text-sm font-medium transition-colors ${
                  isActive
                    ? 'bg-blue-50 text-blue-700'
                    : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
                }`
              }
            >
              <Icon size={16} />
              {label}
            </NavLink>
          ))}
        </nav>

        {/* Footer */}
        <div className="px-4 py-4 border-t border-gray-200 space-y-2">
          {email && (
            <div className="text-xs text-gray-500 truncate">
              <div className="font-medium text-gray-700 truncate">{email}</div>
              <div>{role}</div>
            </div>
          )}
          <Link
            to="/"
            className="block text-xs text-blue-600 hover:text-blue-800"
          >
            ← 채팅으로
          </Link>
        </div>
      </aside>

      {/* Main content */}
      <main className="flex-1 overflow-y-auto">
        <Outlet />
      </main>
    </div>
  )
}
