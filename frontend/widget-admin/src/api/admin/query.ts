import apiClient from '../client'

export interface QueryResult {
  content: string
  intent: string | null
  generatedSql: string | null
}

export const runAdminQuery = (message: string) =>
  apiClient.post<QueryResult>('/api/admin/query', { message }).then(r => r.data)
