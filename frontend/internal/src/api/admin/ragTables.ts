import apiClient from '../client'

export interface RagTable {
  id: number
  sourceTable: string
  sourceType: string
  chunkingStrategy: string
  chunkSize: number
  chunkOverlap: number
  pkColumn: string
  titleColumn: string | null
  contentColumnsJson: string
  metadataColumnsJson: string
  piiMaskingLevel: string
  dataSensitivity: string
  isActive: boolean
  datasourceId: number
  llmStatus: string
  createdAt: string
  updatedAt: string
}

const base = (dsId: number) => `/api/v1/admin/datasources/${dsId}/rag-tables`

export const getRagTables = (dsId: number) =>
  apiClient.get<RagTable[]>(base(dsId)).then(r => r.data)

export const createRagTable = (dsId: number, body: Partial<RagTable>) =>
  apiClient.post<RagTable>(base(dsId), body).then(r => r.data)

export const deleteRagTable = (dsId: number, sourceTable: string) =>
  apiClient.delete(`${base(dsId)}/${encodeURIComponent(sourceTable)}`).then(r => r.data)

export const resyncRagTable = (dsId: number, sourceTable: string) =>
  apiClient.post<void>(`${base(dsId)}/${encodeURIComponent(sourceTable)}/resync`).then(r => r.data)

export const bulkImportRagTables = (dsId: number, tableNames: string[]) =>
  apiClient
    .post<{ imported: string[]; skipped: string[] }>(`${base(dsId)}/bulk-import`, { tableNames })
    .then(r => r.data)

export interface ColumnConfig {
  titleColumn: string | null
  contentColumns: string[]
  metadataColumns: string[]
  pkColumn: string
  chunkingStrategy: string
  chunkSize: number
  chunkOverlap: number
}

export const updateRagTableColumns = (dsId: number, sourceTable: string, config: ColumnConfig) =>
  apiClient
    .patch<RagTable>(`${base(dsId)}/${encodeURIComponent(sourceTable)}/columns`, config)
    .then(r => r.data)

export const updateRagTableStatus = (dsId: number, sourceTable: string, isActive: boolean) =>
  apiClient
    .patch<RagTable>(`${base(dsId)}/${encodeURIComponent(sourceTable)}/status`, { isActive })
    .then(r => r.data)

export const bulkDeleteRagTables = (dsId: number, tableNames: string[]) =>
  apiClient
    .delete<{ succeeded: string[]; failed: string[] }>(`${base(dsId)}/bulk`, { data: { tableNames } })
    .then(r => r.data)

export const bulkResyncRagTables = (dsId: number, tableNames: string[]) =>
  apiClient
    .post<{ succeeded: string[]; failed: string[] }>(`${base(dsId)}/bulk-resync`, { tableNames })
    .then(r => r.data)

export const getRagSyncStatus = (dsId: number) =>
  apiClient.get<{ syncing: boolean }>(`/api/v1/admin/datasources/${dsId}/sync-mode/rag-sync-status`).then(r => r.data)
