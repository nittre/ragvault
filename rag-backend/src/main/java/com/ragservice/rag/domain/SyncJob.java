package com.ragservice.rag.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * sync_jobs 테이블 엔티티.
 *
 * 동기화 작업 단위 추적.
 * trigger_type: 'scheduled', 'manual', 'initial'
 * status: 'running', 'success', 'partial', 'failed'
 */
@Entity
@Table(name = "sync_jobs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "trigger_type", nullable = false, length = 20)
    private String triggerType;

    @Column(name = "triggered_by", length = 200)
    private String triggeredBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "running";

    @Column(name = "records_total")
    @Builder.Default
    private int recordsTotal = 0;

    @Column(name = "records_success")
    @Builder.Default
    private int recordsSuccess = 0;

    @Column(name = "records_failed")
    @Builder.Default
    private int recordsFailed = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    void onCreate() {
        startedAt = Instant.now();
    }
}
