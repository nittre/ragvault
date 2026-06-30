import apiClient from '../client'

export interface SqlLog {
  id: number
  userEmail: string | null
  question: string
  generatedSql: string | null
  validationResult: string | null
  validationReason: string | null
  executionStatus: string | null
  rowCount: number | null
  elapsedMs: number | null
  errorMessage: string | null
  failureCategory: string | null
  createdAt: string
}

export const getSqlLogs = (params: { status?: string; validationResult?: string; limit?: number } = {}) =>
  apiClient
    .get<SqlLog[]>('/api/v1/admin/sql-logs', { params: { limit: 100, ...params } })
    .then(r => r.data)
