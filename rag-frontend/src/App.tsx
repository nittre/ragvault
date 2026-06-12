import { lazy, Suspense } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import PrivateRoute from './components/common/PrivateRoute'
import AdminRoute from './components/common/AdminRoute'

const LoginPage = lazy(() => import('./pages/LoginPage'))
const ChatPage = lazy(() => import('./pages/ChatPage'))
const AdminLayout = lazy(() => import('./components/admin/AdminLayout'))
const UsersPage = lazy(() => import('./pages/admin/UsersPage'))
const RagTablesPage = lazy(() => import('./pages/admin/RagTablesPage'))
const SqlTablesPage = lazy(() => import('./pages/admin/SqlTablesPage'))
const MaskingRulesPage = lazy(() => import('./pages/admin/MaskingRulesPage'))
const SearchConfigPage = lazy(() => import('./pages/admin/SearchConfigPage'))
const DdlEventsPage = lazy(() => import('./pages/admin/DdlEventsPage'))
const AuditLogsPage = lazy(() => import('./pages/admin/AuditLogsPage'))
const UsageStatsPage = lazy(() => import('./pages/admin/UsageStatsPage'))
const ApiKeysPage = lazy(() => import('./pages/admin/ApiKeysPage'))
const ParamLimitsPage = lazy(() => import('./pages/admin/ParamLimitsPage'))

function LoadingFallback() {
  return (
    <div className="flex items-center justify-center h-screen">
      <div className="w-8 h-8 border-4 border-blue-500 border-t-transparent rounded-full animate-spin" />
    </div>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <Suspense fallback={<LoadingFallback />}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            path="/"
            element={
              <PrivateRoute>
                <ChatPage />
              </PrivateRoute>
            }
          />
          <Route
            path="/admin"
            element={
              <AdminRoute>
                <AdminLayout />
              </AdminRoute>
            }
          >
            <Route index element={<Navigate to="/admin/users" replace />} />
            <Route path="users" element={<UsersPage />} />
            <Route path="rag-tables" element={<RagTablesPage />} />
            <Route path="sql-tables" element={<SqlTablesPage />} />
            <Route path="masking-rules" element={<MaskingRulesPage />} />
            <Route path="search-config" element={<SearchConfigPage />} />
            <Route path="ddl-events" element={<DdlEventsPage />} />
            <Route path="audit-logs" element={<AuditLogsPage />} />
            <Route path="usage-stats" element={<UsageStatsPage />} />
            <Route path="api-keys" element={<ApiKeysPage />} />
            <Route path="param-limits" element={<ParamLimitsPage />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </BrowserRouter>
  )
}
