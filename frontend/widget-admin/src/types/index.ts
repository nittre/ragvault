export type Role = 'SUPER_ADMIN' | 'ADMIN' | 'USER'

export interface AdminUser {
  id: string
  email: string
  name: string
  role: Role
  active: boolean
  createdBy?: string
  createdAt: string
  updatedAt: string
}
