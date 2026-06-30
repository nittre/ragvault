package com.ragvault.widget.service;

import com.ragvault.widget.domain.AuditLog;
import com.ragvault.widget.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그 서비스.
 *
 * 모든 기록은 @Async 비동기 처리 — 관리자 액션 응답에 영향 없음.
 * AsyncConfig(@EnableAsync proxyTargetClass=true) 가 이미 선언됨.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    /**
     * 감사 로그 비동기 저장.
     *
     * @param actorEmail 액션을 수행한 사용자 이메일 (SecurityContextHolder 에서 추출)
     * @param action     수행된 액션 (LOGIN, LOGOUT, FAQ_CREATE, ...)
     * @param targetType 대상 리소스 유형 (nullable)
     * @param targetId   대상 리소스 ID (nullable)
     * @param detail     추가 상세 정보 (nullable)
     * @param ipAddress  요청자 IP (HttpServletRequest.getRemoteAddr())
     */
    @Async
    @Transactional
    public void log(String actorEmail, String action,
                    String targetType, String targetId,
                    String detail, String ipAddress) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setActorEmail(actorEmail);
            auditLog.setAction(action);
            auditLog.setTargetType(targetType);
            auditLog.setTargetId(targetId);
            auditLog.setDetail(detail);
            auditLog.setIpAddress(ipAddress);
            auditLogRepository.save(auditLog);
            log.debug("AuditLog saved: actor={}, action={}, target={}/{}",
                    actorEmail, action, targetType, targetId);
        } catch (Exception e) {
            // 감사 로그 실패가 비즈니스 로직을 차단하면 안 됨
            log.error("Failed to save audit log: actor={}, action={}", actorEmail, action, e);
        }
    }
}
