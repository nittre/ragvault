import apiClient from '../client'

export interface SyncJob {
  id: string
  triggerType: string
  triggeredBy: string | null
  status: string
  recordsTotal: number
  recordsSuccess: number
  recordsFailed: number
  errorMessage: string | null
  startedAt: string
  completedAt: string | null
}

export interface FailedBinlogEvent {
  id: number
  eventType: string | null
  tableName: string | null
  sourceId: string | null
  rowCount: number | null
  processed: boolean
  attempt: number
  errorMessage: string | null
  createdAt: string
  processedAt: string | null
}

export const triggerSync = (datasourceId: number) =>
  apiClient.post<SyncJob>('/api/admin/sync/trigger', { datasourceId }).then(r => r.data)

export const getSyncStatus = () =>
  apiClient.get<SyncJob | null>('/api/admin/sync/status').then(r => r.data)

export const triggerInitialSync = (datasourceId: number, tables?: string[]) =>
  apiClient.post<void>('/api/admin/sync/initial', { datasourceId, tables }).then(r => r.data)

export const getFailedSyncEvents = () =>
  apiClient.get<FailedBinlogEvent[]>('/api/admin/sync/failed-events').then(r => r.data)
