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

    private LocalDateTime createdAt;
}
