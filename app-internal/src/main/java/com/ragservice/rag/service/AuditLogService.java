package com.ragservice.rag.service;

import com.ragservice.rag.domain.AuditLog;
import com.ragservice.rag.repository.AuditLogRepository;
import com.ragvault.core.security.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 감사 로그 서비스.
 *
 * 모든 기록은 @Async 비동기 처리 — 채팅 응답에 영향 없음.
 * RagBackendApplication(@EnableAsync proxyTargetClass=true) 가 이미 선언됨.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    /** V6 스키마 코멘트: requestSummary 는 PII 없음 — 최대 50자만 저장 (ADR-0008). */
    private static final int REQUEST_SUMMARY_MAX_LEN = 50;

    private final AuditLogRepository auditLogRepository;
    private final PiiMasker piiMasker;

    /**
     * 감사 로그 비동기 저장.
     *
     * @param userEmail   요청 사용자 이메일 (SecurityContext / ADR-0011)
     * @param action      수행된 액션 ('CHAT', 'SQL_QUERY', 'FILE_UPLOAD', ...)
     * @param intent      라우팅 의도 (QueryRouterService 분류 결과, nullable)
     * @param userMessage 원본 사용자 질문 — 마스킹 후 앞 50자만 저장
     * @param ipAddress   요청자 IP (HttpServletRequest.getRemoteAddr())
     * @param responseId  ResponseRawStorageService 키 (ADR-0010, nullable)
     */
    @Async
    @Transactional
    public void log(String userEmail, String action, String intent,
                     String userMessage, String ipAddress, String responseId) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .userEmail(userEmail)
                    .action(action)
                    .intent(intent)
                    .requestSummary(summarize(userMessage))
                    .ipAddress(ipAddress)
                    .responseId(responseId)
                    .createdAt(LocalDateTime.now())
                    .build();
            auditLogRepository.save(auditLog);
            log.debug("AuditLog saved: user={}, action={}, intent={}", userEmail, action, intent);
        } catch (Exception e) {
            // 감사 로그 실패가 비즈니스 로직을 차단하면 안 됨
            log.error("Failed to save audit log: user={}, action={}", userEmail, action, e);
        }
    }

    private String summarize(String userMessage) {
        if (userMessage == null) return null;
        String masked = piiMasker.mask(userMessage);
        return masked.length() <= REQUEST_SUMMARY_MAX_LEN
                ? masked
                : masked.substring(0, REQUEST_SUMMARY_MAX_LEN);
    }
}
