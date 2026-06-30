import apiClient from '../client'

export interface DataSource {
  id: number
  name: string
  description: string | null
  dbType: string
  host: string
  port: number
  dbName: string
  username: string
  isActive: boolean
  createdAt: string
  updatedAt: string
}

export interface DataSourceRequest {
  name: string
  description?: string
  dbType: string
  host: string
  port: number
  dbName: string
  username: string
  password: string
}

export interface RagTable {
  id: number
  datasourceId: number
  tableName: string
  isActive: boolean
  lastSyncedAt: string | null
}

export interface SyncJob {
  id: number
  datasourceId: number
  tableName: string
  status: 'pending' | 'running' | 'done' | 'failed'
  rowCount: number | null
  errorMsg: string | null
  startedAt: string
  finishedAt: string | null
}

export interface TableInfo {
  tableName: string
  tableComment: string
}

export interface ConnectionTestResult {
  connected: boolean
  message: string
}

export const listDataSources = () =>
  apiClient.get<DataSource[]>('/api/admin/datasources').then(r => r.data)

export const createDataSource = (req: DataSourceRequest) =>
  apiClient.post<DataSource>('/api/admin/datasources', req).then(r => r.data)

export const updateDataSource = (id: number, req: Partial<DataSourceRequest>) =>
  apiClient.patch<DataSource>(`/api/admin/datasources/${id}`, req).then(r => r.data)

export const deleteDataSource = (id: number) =>
  apiClient.delete(`/api/admin/datasources/${id}`)

export const testConnection = (id: number) =>
  apiClient.post<ConnectionTestResult>(`/api/admin/datasources/${id}/test`).then(r => r.data)

export const listTables = (id: number) =>
  apiClient.get<TableInfo[]>(`/api/admin/datasources/${id}/tables`).then(r => r.data)

export const listRagTables = (id: number) =>
  apiClient.get<RagTable[]>(`/api/admin/datasources/${id}/rag-tables`).then(r => r.data)

export const addRagTable = (id: number, tableName: string) =>
  apiClient.post<RagTable>(`/api/admin/datasources/${id}/rag-tables`, { tableName }).then(r => r.data)

export const removeRagTable = (datasourceId: number, ragTableId: number) =>
  apiClient.delete(`/api/admin/datasources/${datasourceId}/rag-tables/${ragTableId}`)

export const triggerSync = (datasourceId: number, tableName: string) =>
  apiClient.post(`/api/admin/datasources/${datasourceId}/sync/${tableName}`).then(r => r.data)

export const listSyncJobs = (datasourceId: number) =>
  apiClient.get<SyncJob[]>(`/api/admin/datasources/${datasourceId}/sync-jobs`).then(r => r.data)
