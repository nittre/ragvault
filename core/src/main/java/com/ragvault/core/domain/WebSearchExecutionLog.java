package com.ragvault.core.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * web_search_execution_log 엔티티.
 * WEB_SEARCH 경로 전체 감사 로그 (sql_execution_log와 동일한 목적).
 *
 * executionStatus: "success" | "error"
 * failureCategory: "SEARXNG_ERROR" | "NO_RESULTS" | "LLM_ERROR" (성공 시 null)
 */
@Entity
@Table(name = "web_search_execution_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSearchExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_email", length = 200)
    private String userEmail;

    @Column(name = "question", columnDefinition = "TEXT", nullable = false)
    private String question;

    @Column(name = "hit_count")
    private Integer hitCount;

    @Column(name = "execution_status", length = 20)
    private String executionStatus;

    @Column(name = "failure_category", length = 30)
    private String failureCategory;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "elapsed_ms")
    private Integer elapsedMs;

    /** ADR-0010: Redis raw storage key */
    @Column(name = "response_id", length = 50)
    private String responseId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
