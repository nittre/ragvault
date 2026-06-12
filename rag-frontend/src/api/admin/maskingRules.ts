import apiClient from '../client'

export type MaskType = 'FULL_MASK' | 'PARTIAL_MASK' | 'EMAIL_MASK' | 'PHONE_MASK'

export interface MaskingRule {
  id: string
  columnPattern: string
  maskType: MaskType
  active: boolean
  createdAt: string
}

export const getMaskingRules = () =>
  apiClient.get<MaskingRule[]>('/api/v1/admin/masking-rules').then(r => r.data)

export const createMaskingRule = (body: { columnPattern: string; maskType: MaskType }) =>
  apiClient.post<MaskingRule>('/api/v1/admin/masking-rules', body).then(r => r.data)

export const deleteMaskingRule = (id: string) =>
  apiClient.delete(`/api/v1/admin/masking-rules/${id}`).then(r => r.data)
