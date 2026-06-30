import apiClient from '../client'

export interface MaskingRule {
  id: number
  name: string
  pattern: string
  replacement: string
  enabled: boolean
  ruleOrder: number
}

export const listMaskingRules = () =>
  apiClient.get<MaskingRule[]>('/api/admin/masking').then(r => r.data)

export const createMaskingRule = (body: Omit<MaskingRule, 'id'>) =>
  apiClient.post<MaskingRule>('/api/admin/masking', body).then(r => r.data)

export const updateMaskingRule = (id: number, body: Omit<MaskingRule, 'id'>) =>
  apiClient.put<MaskingRule>(`/admin/masking/${id}`, body).then(r => r.data)

export const deleteMaskingRule = (id: number) =>
  apiClient.delete(`/admin/masking/${id}`).then(r => r.data)

export const reloadMasking = () =>
  apiClient.post('/api/admin/masking/reload').then(r => r.data)
