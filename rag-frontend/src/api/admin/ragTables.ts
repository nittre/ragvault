import apiClient from '../client'

export interface RagTable {
  id: string
  tableName: string
  description: string
  active: boolean
  createdAt: string
}

export const getRagTables = () =>
  apiClient.get<RagTable[]>('/api/v1/admin/rag-tables').then(r => r.data)

export const createRagTable = (body: { tableName: string; description?: string }) =>
  apiClient.post<RagTable>('/api/v1/admin/rag-tables', body).then(r => r.data)

export const deleteRagTable = (sourceTable: string) =>
  apiClient.delete(`/api/v1/admin/rag-tables/${encodeURIComponent(sourceTable)}`).then(r => r.data)

export const resyncRagTable = (sourceTable: string) =>
  apiClient.post<void>(`/api/v1/admin/rag-tables/${encodeURIComponent(sourceTable)}/resync`).then(r => r.data)
