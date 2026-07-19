package com.ragvault.core.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * binlog_events 테이블 엔티티.
 *
 * 실패한 binlog 이벤트 추적 + 재처리용.
 */
@Entity
@Table(name = "binlog_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BinlogEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", length = 50)
    private String eventType;

    @Column(name = "table_name", length = 100)
    private String tableName;

    @Column(name = "source_id", length = 200)
    private String sourceId;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "processed")
    private boolean processed = false;

    @Column(name = "attempt")
    private int attempt = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
