import type { RagParams } from '../types'

/**
 * 프론트 RagParams(camelCase) 키 ↔ 백엔드 admin_param_limits/ParameterResolver(snake_case) 키 매핑.
 * 이 매핑이 없으면 rag_params가 그대로 전송되어 백엔드 Stage 6가 키를 인식하지 못한다.
 */
export const RAG_PARAM_KEY_TO_BACKEND: Record<keyof RagParams, string> = {
  topK: 'top_k',
  threshold: 'similarity_threshold',
  temperature: 'temperature',
  topP: 'top_p',
  maxTokens: 'max_tokens',
  queryTimeoutSec: 'query_timeout_sec',
  maxResultRows: 'max_result_rows',
  forcePath: 'force_path',
  hybridStyle: 'hybrid_synthesis_style',
  maxHistoryTurns: 'max_history_turns',
}

const BACKEND_TO_RAG_PARAM_KEY: Record<string, keyof RagParams> = Object.fromEntries(
  Object.entries(RAG_PARAM_KEY_TO_BACKEND).map(([front, back]) => [back, front as keyof RagParams])
)

/** RagParams(camelCase) → 백엔드 전송용 snake_case 맵으로 변환. */
export function toBackendRagParams(params: RagParams): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  ;(Object.keys(params) as (keyof RagParams)[]).forEach(key => {
    const value = params[key]
    if (value !== undefined) {
      out[RAG_PARAM_KEY_TO_BACKEND[key]] = value
    }
  })
  return out
}

/** 백엔드 snake_case 키 → RagParams(camelCase) 키. 매핑 없으면 undefined. */
export function backendKeyToRagParamKey(backendKey: string): keyof RagParams | undefined {
  return BACKEND_TO_RAG_PARAM_KEY[backendKey]
}
