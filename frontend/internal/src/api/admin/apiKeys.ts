import apiClient from '../client'
import type { ApiKeyInfo } from '../../types'

export const getApiKeys = () =>
  apiClient.get<ApiKeyInfo[]>('/api/v1/admin/api-keys').then(r => r.data)

export const createApiKey = (body: { name: string; scopes: string; expiresAt: string }) =>
  apiClient.post<ApiKeyInfo & { rawKey: string }>('/api/v1/admin/api-keys', body).then(r => r.data)

export const deleteApiKey = (id: string) =>
  apiClient.delete(`/api/v1/admin/api-keys/${id}`).then(r => r.data)

export const rotateApiKey = (id: string) =>
  apiClient.post<ApiKeyInfo & { rawKey: string }>(`/api/v1/admin/api-keys/${id}/rotate`).then(r => r.data)
