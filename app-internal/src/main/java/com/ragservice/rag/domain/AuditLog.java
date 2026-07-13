package com.ragservice.rag.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid default gen_random_uuid()")
    private UUID id;

    /** ADR-0010: ResponseRawStorageService 키 */
    private String responseId;

    private String userEmail;

    @Column(nullable = false)
    private String action;

    private String intent;

    /** PII 없음 — 최대 50자만 저장 (ADR-0008) */
    private String requestSummary;

    private String ipAddress;

    /** LL-0005: Lombok이 isXxx boolean 필드를 isXxx()로 생성해 Jackson이 "xxx"로 직렬화하므로 컬럼명을 명시한다. */
    @Column(name = "has_context")
    private boolean hasContext;

    @Column(name = "is_blocked")
    private boolean isBlocked;

    private int sourceCount;

    private LocalDateTime createdAt;
}
