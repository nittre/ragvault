import apiClient from './client'
import type { Role } from '../types'

export interface LoginResponse {
  email: string
  role: Role
  expiresAt: string
  passwordChangeRequired: boolean
}

export const login = (args: { email: string; password: string }) =>
  apiClient.post<LoginResponse>('/api/v1/auth/login', args).then(r => r.data)

export const logout = () =>
  apiClient.post('/api/v1/auth/logout').then(() => {})

export const getMe = () =>
  apiClient.get<{ email: string; role: Role }>('/api/admin/me').then(r => r.data)

export const changePassword = (args: {
  email: string
  currentPassword: string
  newPassword: string
}) => apiClient.post('/api/v1/auth/change-password', args).then(() => {})
