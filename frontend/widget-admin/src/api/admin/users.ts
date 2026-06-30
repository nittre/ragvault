import apiClient from '../client'
import type { AdminUser } from '../../types'

export const getUsers = () =>
  apiClient.get<AdminUser[]>('/api/admin/users').then(r => r.data)

export const createUser = (body: { email: string; name: string; role: string }) =>
  apiClient.post<AdminUser>('/api/admin/users', body).then(r => r.data)

export const updateUser = (
  id: string,
  body: { name?: string; role?: string; active?: boolean }
) => apiClient.put<AdminUser>(`/admin/users/${id}`, body).then(r => r.data)

export const deleteUser = (id: string) =>
  apiClient.delete(`/admin/users/${id}`).then(r => r.data)
