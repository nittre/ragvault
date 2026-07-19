import apiClient from '../client'

export interface DdlEvent {
  id: number
  sqlQuery: string
  tableName: string | null
  eventType: string | null
  riskLevel: string | null
  autoApplyAt: string | null
  processedAt: string | null
  processedBy: string | null
  actionTaken: string | null
  notes: string | null
  datasourceId: number | null
  whitelistAppliedSqlAt: string | null
  whitelistAppliedRagAt: string | null
  createdAt: string
}

const base = (dsId: number) => `/api/admin/datasources/${dsId}/ddl-events`

export const getDdlEvents = (dsId: number, all = false) =>
  apiClient.get<DdlEvent[]>(base(dsId), { params: { all } }).then(r => r.data)
