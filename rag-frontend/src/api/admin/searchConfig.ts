import apiClient from '../client'

export interface SearchConfig {
  id: string
  configKey: string
  configValue: string
  description: string
}

export const getSearchConfig = () =>
  apiClient.get<SearchConfig[]>('/api/v1/admin/search-config').then(r => r.data)

export const updateSearchConfig = (key: string, body: { value: string }) =>
  apiClient.put<SearchConfig>(`/api/v1/admin/search-config/${encodeURIComponent(key)}`, body).then(r => r.data)
