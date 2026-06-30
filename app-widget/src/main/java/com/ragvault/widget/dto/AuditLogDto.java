package com.ragvault.widget.dto;

import com.ragvault.widget.domain.AuditLog;

import java.time.format.DateTimeFormatter;

/**
 * 감사 로그 응답 DTO.
 */
public record AuditLogDto(
        Long id,
        String actorEmail,
        String action,
        String targetType,
        String targetId,
        String detail,
        String ipAddress,
        String createdAt
) {
    public static AuditLogDto from(AuditLog log) {
        return new AuditLogDto(
                log.getId(),
                log.getActorEmail(),
                log.getAction(),
                log.getTargetType(),
                log.getTargetId(),
                log.getDetail(),
                log.getIpAddress(),
                log.getCreatedAt() != null
                        ? log.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        : null
        );
    }
}
