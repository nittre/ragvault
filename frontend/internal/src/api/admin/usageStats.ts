import apiClient from '../client'

export interface DailyCount {
  day: string
  count: number
}

export interface UsageStatsSummary {
  totalCount: number
  last7dCount: number
  last30dCount: number
  contextHitRate30d: number
  blockedRate30d: number
  daily30d: DailyCount[]
  routing: {
    RAG: number
    SQL_QUERY: number
    FILE_UPLOAD: number
    HYBRID: number
    WEB_SEARCH: number
    REJECT: number
    OTHER: number
  }
  executions: {
    sqlQuery: number
    webSearch: number
  }
}

export const getSummaryUsageStat = () =>
  apiClient
    .get<UsageStatsSummary>('/api/v1/admin/usage-stats/summary')
    .then(r => r.data)
