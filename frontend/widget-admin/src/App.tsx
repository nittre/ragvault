import { lazy, Suspense } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import AdminRoute from './components/common/AdminRoute'

const LoginPage = lazy(() => import('./pages/LoginPage'))
const AdminLayout = lazy(() => import('./components/admin/AdminLayout'))
const UsersPage = lazy(() => import('./pages/admin/UsersPage'))
const SettingsPage = lazy(() => import('./pages/admin/SettingsPage'))
const KnowledgePage = lazy(() => import('./pages/admin/KnowledgePage'))
const ConversationsPage = lazy(() => import('./pages/admin/ConversationsPage'))
const StatsPage = lazy(() => import('./pages/admin/StatsPage'))
const MaskingPage = lazy(() => import('./pages/admin/MaskingPage'))
const SearchConfigPage = lazy(() => import('./pages/admin/SearchConfigPage'))
const AuditLogPage = lazy(() => import('./pages/admin/AuditLogPage'))
const SiteKeysPage = lazy(() => import('./pages/admin/SiteKeysPage'))
const DataSourcesPage = lazy(() => import('./pages/admin/DataSourcesPage'))
const RagTablesPage = lazy(() => import('./pages/admin/RagTablesPage'))
const SqlTablesPage = lazy(() => import('./pages/admin/SqlTablesPage'))
const QueryConsolePage = lazy(() => import('./pages/admin/QueryConsolePage'))

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
            path="/admin"
            element={
              <AdminRoute>
                <AdminLayout />
              </AdminRoute>
            }
          >
            <Route index element={<Navigate to="/admin/users" replace />} />
            <Route path="users" element={<UsersPage />} />
            <Route path="settings" element={<SettingsPage />} />
            <Route path="knowledge" element={<KnowledgePage />} />
            <Route path="conversations" element={<ConversationsPage />} />
            <Route path="stats" element={<StatsPage />} />
            <Route path="masking" element={<MaskingPage />} />
            <Route path="search" element={<SearchConfigPage />} />
            <Route path="audit" element={<AuditLogPage />} />
            <Route path="widget" element={<SiteKeysPage />} />
            <Route path="datasources" element={<DataSourcesPage />} />
            <Route path="datasources/:dsId/rag-tables" element={<RagTablesPage />} />
            <Route path="datasources/:dsId/sql-tables" element={<SqlTablesPage />} />
            <Route path="query-console" element={<QueryConsolePage />} />
          </Route>
          <Route path="*" element={<Navigate to="/admin/users" replace />} />
        </Routes>
      </Suspense>
    </BrowserRouter>
  )
}
