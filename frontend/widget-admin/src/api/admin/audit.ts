import apiClient from '../client'

export interface AuditLogDto {
  id: number
  actorEmail: string
  action: string
  targetType: string
  targetId: string
  detail: string
  ipAddress: string
  createdAt: string
}

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface AuditLogParams {
  page?: number
  size?: number
}

export const getAuditLogs = (params: AuditLogParams = {}) =>
  apiClient
    .get<Page<AuditLogDto>>('/api/admin/audit', { params: { page: params.page ?? 0, size: params.size ?? 30 } })
    .then(r => r.data)
