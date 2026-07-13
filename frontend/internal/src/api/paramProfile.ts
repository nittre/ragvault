import apiClient from './client'

export interface ParamLimitInfo {
  minValue: number | null
  maxValue: number | null
  locked: boolean
  lockedReason: string | null
  fixedValue: number | null
  defaultValue: string | null
}

export interface ParamProfileResponse {
  params: Record<string, unknown>
  defaults: Record<string, unknown>
  limits: Record<string, ParamLimitInfo>
}

export const getParamProfile = () =>
  apiClient.get<ParamProfileResponse>('/api/v1/user/param-profile').then(r => r.data)
