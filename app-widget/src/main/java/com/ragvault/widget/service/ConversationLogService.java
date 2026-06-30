package com.ragvault.widget.service;

import com.ragvault.widget.domain.ConversationLog;
import com.ragvault.widget.repository.ConversationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 대화 로그 비동기 저장 서비스.
 *
 * @Async 가 동작하려면 AsyncConfig 의 @EnableAsync(proxyTargetClass = true) 가 필요하다.
 * proxyTargetClass=true 미지정 시 JDK 동적 프록시 → 구체 타입 주입 불일치 (LL-0005).
 *
 * 저장 실패는 로그만 남기고 무시 — 대화 응답에 영향 없어야 함.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationLogService {

    private final ConversationLogRepository conversationLogRepository;

    /**
     * 대화 로그 비동기 저장.
     * 호출 스레드를 블로킹하지 않는다.
     */
    @Async
    public void saveAsync(String sessionId, String siteKey, String userMessage,
                          String botResponse, boolean isBlocked, boolean hasContext, int sourceCount) {
        try {
            ConversationLog log = ConversationLog.builder()
                    .sessionId(sessionId)
                    .siteKey(siteKey)
                    .userMessage(userMessage)
                    .botResponse(botResponse)
                    .isBlocked(isBlocked)
                    .hasContext(hasContext)
                    .sourceCount(sourceCount)
                    .build();
            conversationLogRepository.save(log);
        } catch (Exception e) {
            log.warn("Failed to save conversation log (session={}, siteKey={}): {}",
                    sessionId, siteKey, e.getMessage());
        }
    }
}
