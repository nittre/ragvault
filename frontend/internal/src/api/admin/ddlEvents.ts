import apiClient from '../client'

export interface DdlEvent {
  id: string
  tableName: string
  eventType: string
  riskLevel: string
  sqlQuery: string
  analysisResult: string
  processedAt: string
  createdAt: string
}

const base = (dsId: number) => `/api/v1/admin/datasources/${dsId}/ddl-events`

export const getDdlEvents = (dsId: number, all = false) =>
  apiClient.get<DdlEvent[]>(base(dsId), { params: { all } }).then(r => r.data)

