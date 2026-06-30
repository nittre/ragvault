package com.ragvault.widget.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "ds_rag_table")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class DsRagTable {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "datasource_id", nullable = false)
    private Integer datasourceId;

    @Column(name = "table_name", nullable = false, length = 100)
    private String tableName;

    @Builder.Default
    @Column(name = "is_active")
    private boolean isActive = true;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;
}
