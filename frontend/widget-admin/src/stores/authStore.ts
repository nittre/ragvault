import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { Role } from '../types'

interface AuthState {
  email: string | null
  role: Role | null
  isAuthenticated: boolean
  setAuth: (email: string, role: Role) => void
  clearAuth: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      email: null,
      role: null,
      isAuthenticated: false,
      setAuth: (email, role) => set({ email, role, isAuthenticated: true }),
      clearAuth: () => set({ email: null, role: null, isAuthenticated: false }),
    }),
    {
      name: 'ragvault-widget-admin-auth',
      storage: {
        getItem: (key) => {
          const val = sessionStorage.getItem(key)
          return val ? JSON.parse(val) : null
        },
        setItem: (key, val) => sessionStorage.setItem(key, JSON.stringify(val)),
        removeItem: (key) => sessionStorage.removeItem(key),
      },
    }
  )
)
