import apiClient from '../client'

export interface DailyUsageStat {
  date: string
  totalQueries: number
  breakdown: {
    RAG: number
    SQL_QUERY: number
    FILE_UPLOAD: number
  }
}

export const getDailyUsageStat = (date?: string) =>
  apiClient
    .get<DailyUsageStat>('/api/v1/admin/usage-stats/daily', {
      params: date ? { date } : undefined,
    })
    .then(r => r.data)
