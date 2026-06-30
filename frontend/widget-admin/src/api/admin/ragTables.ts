import apiClient from '../client'

export interface RagTable {
  id: number
  datasourceId: number
  sourceTable: string
  sourceType: string
  chunkingStrategy: string
  chunkSize: number
  chunkOverlap: number
  titleColumn: string | null
  contentColumns?: string[]
  contentColumnsJson: string
  metadataColumns?: string[]
  metadataColumnsJson: string
  pkColumn: string
  dataSensitivity: string
  llmStatus: string
  isActive: boolean
  createdAt: string
  updatedAt: string
}

export interface DriftEntry {
  tableName: string
  status: string
  missingColumns: string[]
}

export interface ColumnConfig {
  titleColumn: string | null
  contentColumns: string[]
  metadataColumns: string[]
  pkColumn: string
  chunkingStrategy: string
  chunkSize: number
  chunkOverlap: number
}

const base = (dsId: number) => `/api/admin/datasources/${dsId}/rag-tables`

export const getRagTables = (dsId: number) =>
  apiClient.get<RagTable[]>(base(dsId)).then(r => r.data)

export const createRagTable = (dsId: number, body: Partial<RagTable>) =>
  apiClient.post<RagTable>(base(dsId), body).then(r => r.data)

export const deleteRagTable = (dsId: number, sourceTable: string) =>
  apiClient.delete(`${base(dsId)}/${encodeURIComponent(sourceTable)}`).then(r => r.data)

export const resyncRagTable = (dsId: number, sourceTable: string) =>
  apiClient.post<void>(`${base(dsId)}/${encodeURIComponent(sourceTable)}/resync`).then(r => r.data)

export const updateRagTableStatus = (dsId: number, sourceTable: string, isActive: boolean) =>
  apiClient
    .patch<RagTable>(`${base(dsId)}/${encodeURIComponent(sourceTable)}/status`, { isActive })
    .then(r => r.data)

export const updateRagTableColumns = (dsId: number, sourceTable: string, config: ColumnConfig) =>
  apiClient
    .patch<RagTable>(`${base(dsId)}/${encodeURIComponent(sourceTable)}/columns`, config)
    .then(r => r.data)

export const bulkImportRagTables = (dsId: number, tableNames: string[]) =>
  apiClient
    .post<{ imported: string[]; skipped: string[] }>(`${base(dsId)}/bulk-import`, { tableNames })
    .then(r => r.data)

export const bulkDeleteRagTables = (dsId: number, tableNames: string[]) =>
  apiClient
    .delete<{ succeeded: string[]; failed: string[] }>(`${base(dsId)}/bulk`, { data: { tableNames } })
    .then(r => r.data)

export const bulkResyncRagTables = (dsId: number, tableNames: string[]) =>
  apiClient
    .post<{ succeeded: string[]; failed: string[] }>(`${base(dsId)}/bulk-resync`, { tableNames })
    .then(r => r.data)

export const getRagDriftStatus = (dsId: number) =>
  apiClient.get<DriftEntry[]>(`${base(dsId)}/drift-status`).then(r => r.data)
