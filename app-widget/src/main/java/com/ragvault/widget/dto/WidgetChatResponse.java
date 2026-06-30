package com.ragvault.widget.dto;

import java.util.List;
import com.ragvault.core.dto.CitationSource;

/**
 * POST /v1/widget/chat 응답 DTO — OpenAI 호환.
 *
 * widget/chat.html 이 choices[0].message.content 를 읽는다.
 * citations: RAG 출처 목록 (선택적).
 */
public record WidgetChatResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        List<CitationSource> citations
) {
    public record Choice(int index, ChatMessage message, String finish_reason) {}
}
