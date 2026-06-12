import apiClient from '../client'

export interface DdlEvent {
  id: string
  tableName: string
  eventType: string
  impactScore: number
  analysisResult: string
  processedAt: string
  createdAt: string
}

export const getDdlEvents = () =>
  apiClient.get<DdlEvent[]>('/api/v1/admin/ddl-events').then(r => r.data)

export const getDdlEventAnalysis = (id: string) =>
  apiClient.get<{ analysisResult: string }>(`/api/v1/admin/ddl-events/${id}/analysis`).then(r => r.data)

export const triggerDdlEvent = (id: string) =>
  apiClient.post<void>(`/api/v1/admin/ddl-events/${id}/trigger`).then(r => r.data)

export const dismissDdlEvent = (id: string) =>
  apiClient.post<void>(`/api/v1/admin/ddl-events/${id}/dismiss`).then(r => r.data)
