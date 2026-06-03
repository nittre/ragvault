package com.ragservice.rag.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * sql_execution_log 엔티티.
 * SQL 경로 전체 감사 로그 (ADR-0007, ADR-0010).
 *
 * responseId: Redis raw storage key (ADR-0010).
 * validationResult: "allowed" | "denied"
 * executionStatus:  "success" | "timeout" | "error"
 */
@Entity
@Table(name = "sql_execution_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_email", length = 200)
    private String userEmail;

    @Column(name = "api_key_id")
    private UUID apiKeyId;

    @Column(name = "intent", length = 20)
    private String intent;

    @Column(name = "question", columnDefinition = "TEXT", nullable = false)
    private String question;

    @Column(name = "generated_sql", columnDefinition = "TEXT")
    private String generatedSql;

    @Column(name = "validation_result", length = 50)
    private String validationResult;

    @Column(name = "validation_reason", columnDefinition = "TEXT")
    private String validationReason;

    @Column(name = "execution_status", length = 20)
    private String executionStatus;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "elapsed_ms")
    private Integer elapsedMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** ADR-0010: Redis raw storage key */
    @Column(name = "response_id", length = 50)
    private String responseId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
