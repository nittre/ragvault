import apiClient from '../client'

export interface MaskingRule {
  id: number
  name: string
  pattern: string
  replacement: string
  level: 'standard' | 'aggressive'
  enabled: boolean
  sortOrder: number
  datasourceId: number | null
  createdAt: string
  updatedAt: string
}

export interface SuggestedMaskingRule {
  name: string
  pattern: string
  replacement: string
  level: 'standard' | 'aggressive'
  detectedInColumns: string[]
}

const base = (dsId: number) => `/api/v1/admin/datasources/${dsId}/masking-rules`

export const getMaskingRules = (dsId: number) =>
  apiClient.get<MaskingRule[]>(base(dsId)).then(r => r.data)

export const createMaskingRule = (dsId: number, body: Omit<MaskingRule, 'id' | 'datasourceId' | 'createdAt' | 'updatedAt'>) =>
  apiClient.post<MaskingRule>(base(dsId), body).then(r => r.data)

export const updateMaskingRule = (
  dsId: number,
  id: number,
  body: Partial<Omit<MaskingRule, 'id' | 'datasourceId' | 'createdAt' | 'updatedAt'>>,
) => apiClient.patch<MaskingRule>(`${base(dsId)}/${id}`, body).then(r => r.data)

export const deleteMaskingRule = (dsId: number, id: number) =>
  apiClient.delete(`${base(dsId)}/${id}`).then(r => r.data)

export const suggestMaskingRules = (dsId: number) =>
  apiClient.get<SuggestedMaskingRule[]>(`${base(dsId)}/suggest`).then(r => r.data)

export const bulkCreateMaskingRules = (
  dsId: number,
  rules: Array<Omit<MaskingRule, 'id' | 'datasourceId' | 'createdAt' | 'updatedAt'>>,
) =>
  apiClient.post<MaskingRule[]>(`${base(dsId)}/bulk`, rules).then(r => r.data)
