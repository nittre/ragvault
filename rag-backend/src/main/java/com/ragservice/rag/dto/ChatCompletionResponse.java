package com.ragservice.rag.dto;

import java.util.List;

/**
 * OpenAI 호환 /v1/chat/completions 동기 응답 DTO.
 *
 * citations  — 출처 정보 (RAG 청크)
 * intent     — "RAG" | "SQL" | "HYBRID" (M3 추가)
 * responseId — ADR-0010 Redis raw storage key (M3 추가)
 * generatedSql — SQL/HYBRID 경로에서 생성된 SQL, RAG 는 null (M3 추가)
 */
public record ChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        List<CitationSource> citations,
        String intent,
        String responseId,
        String generatedSql
) {
    public record Choice(int index, ChatMessage message, String finish_reason) {}
}
