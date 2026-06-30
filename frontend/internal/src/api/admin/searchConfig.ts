import apiClient from '../client'

export interface SearchConfig {
  id: string
  configKey: string
  configValue: string
  description: string
}

const base = (dsId: number) => `/api/v1/admin/datasources/${dsId}/search-config`

export const getSearchConfig = (dsId: number) =>
  apiClient.get<SearchConfig[]>(base(dsId)).then(r => r.data)

export const updateSearchConfig = (dsId: number, key: string, body: { value: string }) =>
  apiClient.put<SearchConfig>(`${base(dsId)}/${encodeURIComponent(key)}`, body).then(r => r.data)
