package com.ragservice.rag.dto;

/**
 * 대화 이력 메시지 DTO.
 * RagService 내부에서 사용.
 */
public record MessageDto(String role, String content) {}
