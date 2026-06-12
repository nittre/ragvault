import { create } from 'zustand'
import type { RagParams } from '../types'

interface ParamState {
  params: RagParams
  systemPrompt: string
  isPanelOpen: boolean
  setParams: (p: Partial<RagParams>) => void
  setSystemPrompt: (s: string) => void
  togglePanel: () => void
  resetParams: () => void
}

export const useParamStore = create<ParamState>()((set) => ({
  params: {},
  systemPrompt: '',
  isPanelOpen: false,
  setParams: (p) => set(s => ({ params: { ...s.params, ...p } })),
  setSystemPrompt: (systemPrompt) => set({ systemPrompt }),
  togglePanel: () => set(s => ({ isPanelOpen: !s.isPanelOpen })),
  resetParams: () => set({ params: {} }),
}))
