export type Role = 'SUPER_ADMIN' | 'ADMIN' | 'USER'

export interface AdminUser {
  email: string
  name: string
  role: Role
  active: boolean
  createdBy?: string
  createdAt: string
}
