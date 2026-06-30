import apiClient from '../client'

export interface SiteKey {
  id: number
  siteKey: string
  label: string
  active: boolean
  brandColor: string
  botName: string
  greeting: string
  logoUrl?: string
  allowedOrigins?: string
  createdAt: string
}

export interface CreateSiteKeyBody {
  label: string
  brandColor?: string
  botName?: string
  greeting?: string
  logoUrl?: string
}

export interface UpdateSiteKeyBody {
  label: string
  active: boolean
  brandColor: string
  botName: string
  greeting: string
  logoUrl?: string
}

export const listSiteKeys = () =>
  apiClient.get<SiteKey[]>('/api/admin/sitekeys').then(r => r.data)

export const createSiteKey = (body: CreateSiteKeyBody) =>
  apiClient.post<SiteKey>('/api/admin/sitekeys', body).then(r => r.data)

export const updateSiteKey = (id: number, body: UpdateSiteKeyBody) =>
  apiClient.put<SiteKey>(`/admin/sitekeys/${id}`, body).then(r => r.data)

export const deleteSiteKey = (id: number) =>
  apiClient.delete(`/admin/sitekeys/${id}`)

export const activateSiteKey = (id: number) =>
  apiClient.post<SiteKey>(`/admin/sitekeys/${id}/activate`).then(r => r.data)

export const deactivateSiteKey = (id: number) =>
  apiClient.post<SiteKey>(`/admin/sitekeys/${id}/deactivate`).then(r => r.data)
