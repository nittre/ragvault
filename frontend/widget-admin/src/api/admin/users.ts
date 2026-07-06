import apiClient from '../client'
import type { AdminUser } from '../../types'

export const getUsers = () =>
  apiClient.get<AdminUser[]>('/api/admin/users').then(r => r.data)

export const createUser = (body: { email: string; name: string; role: string; password: string }) =>
  apiClient.post<AdminUser>('/api/admin/users', body).then(r => r.data)

export const updateUser = (
  email: string,
  body: { name?: string; role?: string; active?: boolean }
) => apiClient.put<AdminUser>(`/api/admin/users/${encodeURIComponent(email)}`, body).then(r => r.data)

export const deleteUser = (email: string) =>
  apiClient.delete(`/api/admin/users/${encodeURIComponent(email)}`).then(r => r.data)

export const resetPassword = (email: string, newPassword: string) =>
  apiClient
    .post(`/api/admin/users/${encodeURIComponent(email)}/reset-password`, { newPassword })
    .then(() => {})
