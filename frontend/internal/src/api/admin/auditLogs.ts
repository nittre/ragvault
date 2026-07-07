import apiClient from '../client'

export interface AuditLog {
  id: string
  userEmail: string
  action: string
  intent: string | null
  requestSummary: string | null
  responseId: string | null
  ipAddress: string | null
  createdAt: string
}

interface AuditLogPage {
  data: AuditLog[]
  total: number
  page: number
  size: number
}

export const getAuditLogs = (page = 0, size = 50) =>
  apiClient
    .get<AuditLogPage>('/api/v1/admin/audit-logs', { params: { page, size } })
    .then(r => r.data)
