import apiClient from './client'
import type { RagParams, CitationSource, Intent } from '../types'

interface ChatMessage { role: string; content: string }
interface ChatRequest {
  model: string; messages: ChatMessage[]
  rag_params?: RagParams; file_ids?: string[]; images?: string[]
  routing_hint?: string
}
interface ChatResponse {
  id: string; object: string; created: number; model: string
  choices: Array<{ index: number; message: ChatMessage; finish_reason: string }>
  citations: CitationSource[]; intent: Intent; responseId: string; generatedSql?: string | null; sourceUrls?: string[] | null
}
export interface FileUploadResponse { id: string; filename: string; size: number }

export const sendMessage = (req: ChatRequest) =>
  apiClient.post<ChatResponse>('/v1/chat/completions', req).then(r => r.data)

export const uploadFile = (file: File) => {
  const fd = new FormData(); fd.append('file', file)
  return apiClient.post<FileUploadResponse>('/v1/files', fd).then(r => r.data)
}
