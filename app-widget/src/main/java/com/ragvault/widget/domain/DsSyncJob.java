package com.ragvault.widget.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "ds_sync_job")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class DsSyncJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "datasource_id", nullable = false)
    private Integer datasourceId;

    @Column(name = "table_name", nullable = false, length = 100)
    private String tableName;

    @Column(nullable = false, length = 20)
    private String status; // pending / running / done / failed

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
