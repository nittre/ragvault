export type Role = 'SUPER_ADMIN' | 'ADMIN' | 'USER'
export type Intent = 'RAG' | 'SQL' | 'HYBRID' | 'IMAGE' | 'FILE' | 'WEB_SEARCH'
export type ForcePath = 'AUTO' | 'FORCE_RAG' | 'FORCE_SQL' | 'FORCE_HYBRID'
export type HybridStyle = 'BALANCED' | 'SQL_FIRST' | 'RAG_FIRST'

export interface CitationSource { title: string; source: string; score: number }
export interface Message {
  id: string; role: 'user' | 'assistant' | 'system'; content: string
  citations?: CitationSource[]; intent?: Intent; responseId?: string
  generatedSql?: string | null; sourceUrls?: string[] | null; timestamp: number; elapsedMs?: number
  routingHint?: 'FORCE_RAG' | 'FORCE_WEB' | 'FORCE_SQL'
  attachedFileNames?: string[]
}
export interface Conversation { id: string; title: string; createdAt: number; updatedAt: number; pinned?: boolean }
export interface RagParams {
  topK?: number; threshold?: number; temperature?: number; topP?: number
  maxTokens?: number; queryTimeoutSec?: number; maxResultRows?: number
  forcePath?: ForcePath; hybridStyle?: HybridStyle; maxHistoryTurns?: number
}
export interface RagUser {
  id: string; email: string; name: string; role: Role; active: boolean
  createdBy?: string; createdAt: string; updatedAt: string
}
export interface ApiKeyInfo {
  id: string; name: string; keyPrefix: string; scopes: string; active: boolean
  expiresAt: string; lastUsedAt?: string; createdAt: string; rawKey?: string
}
export interface ParamProfile {
  params: Record<string, unknown>
  defaults: Record<string, unknown>
  limits: Record<string, { min?: number; max?: number; locked: boolean; lockedReason?: string }>
}
