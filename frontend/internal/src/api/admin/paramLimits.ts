import apiClient from '../client'

export interface ParamLimit {
  id: string
  paramName: string
  minValue: number
  maxValue: number
  locked: boolean
  lockedReason: string
  description: string
  fixedValue: number | null
}

export const getParamLimits = () =>
  apiClient.get<ParamLimit[]>('/api/v1/admin/param-limits').then(r => r.data)

export const updateParamLimit = (
  paramName: string,
  body: { minValue?: number; maxValue?: number; fixedValue?: number | null }
) =>
  apiClient.put<ParamLimit>(`/api/v1/admin/param-limits/${encodeURIComponent(paramName)}`, body).then(r => r.data)

export const lockParam = (paramKey: string, body: { reason?: string }) =>
  apiClient.put<ParamLimit>(`/api/v1/admin/param-limits/${encodeURIComponent(paramKey)}/lock`, body).then(r => r.data)

export const unlockParam = (paramKey: string) =>
  apiClient.delete(`/api/v1/admin/param-limits/${encodeURIComponent(paramKey)}/lock`).then(r => r.data)
