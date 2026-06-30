import apiClient from '../client'

export interface ModeEntry {
  autoSyncEnabled: boolean
  disabledAt: string | null
}

export interface SyncModeResponse {
  sql: ModeEntry
  rag: ModeEntry
}

export interface DriftEntry {
  tableName: string
  status: 'ok' | 'table_missing' | 'column_mismatch'
  missingColumns: string[]
}

const base = (dsId: number) => `/api/v1/admin/datasources/${dsId}/sync-mode`

export const getSyncMode = (dsId: number) =>
  apiClient.get<SyncModeResponse>(base(dsId)).then(r => r.data)

export const updateSyncMode = (dsId: number, tableType: 'sql' | 'rag', autoSyncEnabled: boolean) =>
  apiClient.put<SyncModeResponse>(base(dsId), { tableType, autoSyncEnabled }).then(r => r.data)

export const replaySyncMode = (dsId: number, tableType: 'sql' | 'rag') =>
  apiClient.post<{ applied: number; skipped: number }>(`${base(dsId)}/replay`, { tableType }).then(r => r.data)

export const getSqlDriftStatus = (dsId: number) =>
  apiClient.get<DriftEntry[]>(`/api/v1/admin/datasources/${dsId}/sql-tables/drift-status`).then(r => r.data)

export const getRagDriftStatus = (dsId: number) =>
  apiClient.get<DriftEntry[]>(`/api/v1/admin/datasources/${dsId}/rag-tables/drift-status`).then(r => r.data)
