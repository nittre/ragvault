import apiClient from '../client'

export interface SearchConfigItem {
  key: string
  value: string
  description: string
}

export const getSearchConfig = () =>
  apiClient.get<SearchConfigItem[]>('/api/admin/search').then(r => r.data)

export const updateSearchConfig = (key: string, value: string) =>
  apiClient.put<SearchConfigItem>(`/admin/search/${key}`, { value }).then(r => r.data)
