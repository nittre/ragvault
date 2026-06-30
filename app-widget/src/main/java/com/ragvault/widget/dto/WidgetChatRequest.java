package com.ragvault.widget.dto;

import java.util.List;

/**
 * POST /v1/widget/chat 요청 DTO.
 *
 * messages: OpenAI 호환 메시지 목록. 마지막 user 메시지가 현재 질문.
 * X-Site-Key 헤더는 필터에서 별도 검증.
 */
public record WidgetChatRequest(
        List<ChatMessage> messages
) {}
