import apiClient from '../client'

export interface SyncStatus {
  running: boolean
  lastSync: string
}

export interface FailedSyncEvent {
  id: string
  tableName: string
  errorMessage: string
  createdAt: string
}

export const triggerSync = () =>
  apiClient.post<void>('/api/v1/admin/sync/trigger').then(r => r.data)

export const getSyncStatus = () =>
  apiClient.get<SyncStatus>('/api/v1/admin/sync/status').then(r => r.data)

export const triggerInitialSync = () =>
  apiClient.post<void>('/api/v1/admin/sync/initial').then(r => r.data)

export const getFailedSyncEvents = () =>
  apiClient.get<FailedSyncEvent[]>('/api/v1/admin/sync/failed-events').then(r => r.data)
