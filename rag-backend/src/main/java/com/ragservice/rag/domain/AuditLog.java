package com.ragservice.rag.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
