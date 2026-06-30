import apiClient from '../client'
import type { RagUser } from '../../types'

export const getUsers = () =>
  apiClient.get<RagUser[]>('/api/v1/admin/users').then(r => r.data)

export const createUser = (body: { email: string; name: string; role: string }) =>
  apiClient.post<RagUser>('/api/v1/admin/users', body).then(r => r.data)

export const updateUser = (email: string, body: { name?: string; role?: string; active?: boolean }) =>
  apiClient.put<RagUser>(`/api/v1/admin/users/${encodeURIComponent(email)}`, body).then(r => r.data)

export const deleteUser = (email: string) =>
  apiClient.delete(`/api/v1/admin/users/${encodeURIComponent(email)}`).then(r => r.data)
