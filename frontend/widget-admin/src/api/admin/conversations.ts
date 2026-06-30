import apiClient from '../client'

export interface ConversationLogDto {
  id: number
  sessionId: string
  siteKey: string
  userMessage: string
  botResponse: string
  isBlocked: boolean
  hasContext: boolean
  sourceCount: number
  createdAt: string
}

export interface DailyCount {
  day: string
  count: number
}

export interface StatsDto {
  totalCount: number
  last7dCount: number
  last30dCount: number
  contextHitRate30d: number
  blockedRate30d: number
  daily30d: DailyCount[]
}

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export const getConversations = (params: {
  page?: number
  size?: number
  siteKey?: string
}) =>
  apiClient
    .get<Page<ConversationLogDto>>('/api/admin/conversations', { params })
    .then(r => r.data)

export const getStats = () =>
  apiClient.get<StatsDto>('/api/admin/conversations/stats').then(r => r.data)
