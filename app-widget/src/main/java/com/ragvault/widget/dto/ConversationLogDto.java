package com.ragvault.widget.dto;

import com.ragvault.widget.domain.ConversationLog;

import java.time.Instant;

/**
 * 대화 로그 응답 DTO.
 *
 * userMessage 최대 100자, botResponse 최대 200자 truncate.
 * Jackson Page 직렬화를 위해 별도 record 로 선언.
 */
public record ConversationLogDto(
        Long id,
        String sessionId,
        String siteKey,
        String userMessage,
        String botResponse,
        boolean isBlocked,
        boolean hasContext,
        int sourceCount,
        Instant createdAt
) {
    private static final int MAX_USER_MESSAGE = 100;
    private static final int MAX_BOT_RESPONSE = 200;

    public static ConversationLogDto from(ConversationLog log) {
        return new ConversationLogDto(
                log.getId(),
                log.getSessionId(),
                log.getSiteKey(),
                truncate(log.getUserMessage(), MAX_USER_MESSAGE),
                truncate(log.getBotResponse(), MAX_BOT_RESPONSE),
                log.isBlocked(),
                log.isHasContext(),
                log.getSourceCount(),
                log.getCreatedAt()
        );
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
