import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { v4 as uuidv4 } from 'uuid'
import type { Conversation, Message } from '../types'

interface ChatState {
  conversations: Conversation[]
  activeConvId: string | null
  messages: Record<string, Message[]>
  createConversation: () => string
  setActiveConv: (id: string) => void
  addMessage: (convId: string, msg: Message) => void
  updateLastAssistantMessage: (convId: string, extras: Partial<Message>) => void
  deleteConversation: (id: string) => void
  updateConvTitle: (convId: string, title: string) => void
  clearAll: () => void
}

export const useChatStore = create<ChatState>()(
  persist(
    (set, get) => ({
      conversations: [],
      activeConvId: null,
      messages: {},

      createConversation: () => {
        const id = uuidv4()
        const now = Date.now()
        const conv: Conversation = { id, title: '새 대화', createdAt: now, updatedAt: now }
        set(s => ({ conversations: [conv, ...s.conversations], activeConvId: id }))
        return id
      },

      setActiveConv: (id) => set({ activeConvId: id }),

      addMessage: (convId, msg) =>
        set(s => ({
          messages: {
            ...s.messages,
            [convId]: [...(s.messages[convId] ?? []), msg],
          },
          conversations: s.conversations.map(c =>
            c.id === convId ? { ...c, updatedAt: Date.now() } : c
          ),
        })),

      updateLastAssistantMessage: (convId, extras) =>
        set(s => {
          const msgs = s.messages[convId] ?? []
          const idx = [...msgs].reverse().findIndex(m => m.role === 'assistant')
          if (idx === -1) return s
          const realIdx = msgs.length - 1 - idx
          const updated = msgs.map((m, i) => i === realIdx ? { ...m, ...extras } : m)
          return { messages: { ...s.messages, [convId]: updated } }
        }),

      deleteConversation: (id) =>
        set(s => {
          const conversations = s.conversations.filter(c => c.id !== id)
          const messages = { ...s.messages }
          delete messages[id]
          const activeConvId = s.activeConvId === id
            ? (conversations[0]?.id ?? null)
            : s.activeConvId
          return { conversations, messages, activeConvId }
        }),

      updateConvTitle: (convId, title) =>
        set(s => ({
          conversations: s.conversations.map(c =>
            c.id === convId ? { ...c, title } : c
          ),
        })),

      clearAll: () => set({ conversations: [], activeConvId: null, messages: {} }),
    }),
    { name: 'rag-chat' }
  )
)
