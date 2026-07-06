import { lazy, Suspense } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import PrivateRoute from './components/common/PrivateRoute'
import AdminRoute from './components/common/AdminRoute'

const LoginPage = lazy(() => import('./pages/LoginPage'))
const ChatPage = lazy(() => import('./pages/ChatPage'))
const SettingsPage = lazy(() => import('./pages/SettingsPage'))
const AdminLayout = lazy(() => import('./components/admin/AdminLayout'))
const UsersPage = lazy(() => import('./pages/admin/UsersPage'))
const DataSourcesPage = lazy(() => import('./pages/admin/DataSourcesPage'))
const RagTablesPage = lazy(() => import('./pages/admin/RagTablesPage'))
const SqlTablesPage = lazy(() => import('./pages/admin/SqlTablesPage'))
const KnowledgePage = lazy(() => import('./pages/admin/KnowledgePage'))
const MaskingRulesPage = lazy(() => import('./pages/admin/MaskingRulesPage'))
const SearchConfigPage = lazy(() => import('./pages/admin/SearchConfigPage'))
const DdlEventsPage = lazy(() => import('./pages/admin/DdlEventsPage'))
const AuditLogsPage = lazy(() => import('./pages/admin/AuditLogsPage'))
const SqlLogsPage = lazy(() => import('./pages/admin/SqlLogsPage'))
const UsageStatsPage = lazy(() => import('./pages/admin/UsageStatsPage'))
const ApiKeysPage = lazy(() => import('./pages/admin/ApiKeysPage'))
const ParamLimitsPage = lazy(() => import('./pages/admin/ParamLimitsPage'))
const KnowledgeDocsPage = lazy(() => import('./pages/admin/KnowledgeDocsPage'))

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
            path="/settings"
            element={
              <PrivateRoute>
                <SettingsPage />
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

            {/* 전역 */}
            <Route path="users" element={<UsersPage />} />
            <Route path="knowledge" element={<KnowledgeDocsPage />} />
            <Route path="audit-logs" element={<AuditLogsPage />} />
            <Route path="sql-logs" element={<SqlLogsPage />} />
            <Route path="usage-stats" element={<UsageStatsPage />} />
            <Route path="api-keys" element={<ApiKeysPage />} />
            <Route path="param-limits" element={<ParamLimitsPage />} />

            {/* 데이터소스 */}
            <Route path="ds" element={<DataSourcesPage />} />
            <Route path="ds/:dsId/rag-tables" element={<RagTablesPage />} />
            <Route path="ds/:dsId/sql-tables" element={<SqlTablesPage />} />
            <Route path="ds/:dsId/knowledge" element={<KnowledgePage />} />
            <Route path="ds/:dsId/masking-rules" element={<MaskingRulesPage />} />
            <Route path="ds/:dsId/ddl-events" element={<DdlEventsPage />} />
            <Route path="ds/:dsId/search-config" element={<SearchConfigPage />} />

            {/* 구버전 URL 리다이렉트 */}
            <Route path="datasources" element={<Navigate to="/admin/ds" replace />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </BrowserRouter>
  )
}
