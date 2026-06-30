import { Navigate } from 'react-router-dom'
import { useAuthStore } from '../../stores/authStore'

interface Props { children: React.ReactNode }

export default function AdminRoute({ children }: Props) {
  const { isAuthenticated, role } = useAuthStore()

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />
  }

  if (role !== 'ADMIN' && role !== 'SUPER_ADMIN') {
    return (
      <div className="flex items-center justify-center h-screen text-gray-500">
        403 — 접근 권한이 없습니다.
      </div>
    )
  }

  return <>{children}</>
}
