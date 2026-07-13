import apiClient from './client'
import type { RagParams, CitationSource, Intent } from '../types'
import { toBackendRagParams } from '../utils/ragParamKeys'

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
export interface FileUploadResponse { id: string; object: string; status: string; token_count: number }

export const sendMessage = (req: ChatRequest) => {
  const payload = req.rag_params
    ? { ...req, rag_params: toBackendRagParams(req.rag_params) }
    : req
  return apiClient.post<ChatResponse>('/v1/chat/completions', payload).then(r => r.data)
}

export const uploadFile = (file: File) => {
  const fd = new FormData(); fd.append('file', file)
  return apiClient.post<FileUploadResponse>('/v1/files', fd).then(r => r.data)
}
