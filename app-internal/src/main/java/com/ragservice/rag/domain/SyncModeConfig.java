package com.ragservice.rag.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "sync_mode_config")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class SyncModeConfig {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "datasource_id", nullable = false)
    private Integer datasourceId;

    @Column(name = "table_type", nullable = false, length = 10)
    private String tableType; // "sql" | "rag"

    @Column(name = "auto_sync_enabled", nullable = false)
    private boolean autoSyncEnabled = false;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "created_at") private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate  void onUpdate() { updatedAt = Instant.now(); }
}
