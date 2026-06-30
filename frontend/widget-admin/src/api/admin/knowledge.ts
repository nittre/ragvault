import apiClient from '../client'

export interface KnowledgeFileInfo {
  name: string
  sizeBytes: number
  lastModified: string
}

export interface KnowledgeContent {
  name: string
  content: string
}

const TEXT_EXTS = new Set(['md', 'txt'])

export function isTextFile(name: string): boolean {
  const ext = name.split('.').pop()?.toLowerCase() ?? ''
  return TEXT_EXTS.has(ext)
}

export function extOf(name: string): string {
  return name.split('.').pop()?.toLowerCase() ?? ''
}

export const listKnowledge = () =>
  apiClient.get<KnowledgeFileInfo[]>('/api/admin/knowledge').then(r => r.data)

export const getKnowledgeContent = (fileId: string) =>
  apiClient.get<KnowledgeContent>(`/api/admin/knowledge/${fileId}`).then(r => r.data)

export const createKnowledge = (body: { name: string; content: string }) =>
  apiClient.post<{ name: string; status: string }>('/api/admin/knowledge', body).then(r => r.data)

export const updateKnowledge = (fileId: string, body: { content: string }) =>
  apiClient.put<{ name: string; status: string }>(`/api/admin/knowledge/${fileId}`, body).then(r => r.data)

export const deleteKnowledge = (fileId: string) =>
  apiClient.delete(`/api/admin/knowledge/${fileId}`).then(r => r.data)

export const reloadKnowledge = (fileId: string) =>
  apiClient.post(`/api/admin/knowledge/${fileId}/reload`).then(r => r.data)

export const reloadAllKnowledge = () =>
  apiClient.post('/api/admin/knowledge/reload').then(r => r.data)

export const uploadKnowledge = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return apiClient
    .post<{ name: string; status: string }>('/api/admin/knowledge/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    .then(r => r.data)
}
