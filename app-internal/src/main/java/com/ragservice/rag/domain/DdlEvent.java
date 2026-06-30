package com.ragservice.rag.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * ddl_events 테이블 엔티티.
 *
 * DDL 이벤트 하이브리드 처리:
 * - LOW: Discord 알림 후 자동 처리
 * - MEDIUM: Discord 경고 + 7일 내 관리자 검토 (미응답 시 자동 처리)
 * - HIGH: Discord 긴급 + 즉시 관리자 검토 필요
 */
@Entity
@Table(name = "ddl_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DdlEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sql_query", nullable = false, columnDefinition = "TEXT")
    private String sqlQuery;

    @Column(name = "table_name", length = 100)
    private String tableName;

    @Column(name = "event_type", length = 50)
    private String eventType;

    @Column(name = "risk_level", length = 20)
    private String riskLevel;

    @Column(name = "auto_apply_at")
    private Instant autoApplyAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "processed_by", length = 200)
    private String processedBy;

    @Column(name = "action_taken", length = 50)
    private String actionTaken;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "datasource_id")
    private Integer datasourceId;

    @Column(name = "whitelist_applied_sql_at")
    private Instant whitelistAppliedSqlAt;

    @Column(name = "whitelist_applied_rag_at")
    private Instant whitelistAppliedRagAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
