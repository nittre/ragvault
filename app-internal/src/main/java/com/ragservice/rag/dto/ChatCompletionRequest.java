package com.ragservice.rag.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 호환 /v1/chat/completions 요청 DTO.
 * M4: images (base64), fileIds 추가.
 * M5-2: rag_params 추가 (Stage 6 요청별 override, ADR-0005).
 *
 * 우선순위: ragParams.temperature 가 있으면 top-level temperature 무시.
 * top-level temperature / max_tokens 는 하위 호환용으로 유지.
 */
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        Boolean stream,
        Integer max_tokens,
        Double temperature,
        List<String> images,                                        // IMAGE 경로: base64 인코딩 이미지
        @JsonAlias("file_ids") List<String> fileIds,                // FILE 경로: 업로드된 file_id 목록
        @JsonProperty("rag_params") Map<String, Object> ragParams,  // M5-2: Stage 6 요청별 override
        @JsonProperty("routing_hint") String routingHint            // /rag·/web 슬래시 커맨드 강제 라우팅
) {}
