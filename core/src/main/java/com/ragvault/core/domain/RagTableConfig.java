package com.ragvault.core.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * rag_table_config 테이블 엔티티.
 *
 * RAG 대상 MySQL 테이블 동적 관리.
 * data_sensitivity='restricted' → Phase 0 거부.
 *
 * 멀티 데이터소스 전제: (datasource_id, source_table) 복합 UNIQUE.
 */
@Entity
@Table(name = "rag_table_config",
        uniqueConstraints = @UniqueConstraint(columnNames = {"datasource_id", "source_table"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagTableConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "datasource_id")  // null = legacy 단일 datasource fallback
    private Integer datasourceId;

    @Column(name = "source_table", nullable = false, length = 100)
    private String sourceTable;

    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @Builder.Default
    @Column(name = "chunking_strategy", nullable = false, length = 50)
    private String chunkingStrategy = "recursive";

    @Builder.Default
    @Column(name = "chunk_size", nullable = false)
    private int chunkSize = 500;

    @Builder.Default
    @Column(name = "chunk_overlap", nullable = false)
    private int chunkOverlap = 50;

    @Column(name = "title_column", length = 100)
    private String titleColumn;

    /**
     * content_columns (TEXT) — 쉼표 구분 문자열로 저장.
     */
    @Column(name = "content_columns", columnDefinition = "TEXT")
    private String contentColumnsJson;

    @Column(name = "metadata_columns", columnDefinition = "TEXT")
    private String metadataColumnsJson;

    @Builder.Default
    @Column(name = "pk_column", nullable = false, length = 100)
    private String pkColumn = "id";

    @Builder.Default
    @Column(name = "pii_masking_level", length = 20)
    private String piiMaskingLevel = "standard";

    @Builder.Default
    @Column(name = "data_sensitivity", nullable = false, length = 20)
    private String dataSensitivity = "internal";

    @Builder.Default
    @Column(name = "llm_status", length = 20)
    private String llmStatus = "done";

    @Builder.Default
    @JsonProperty("isActive")
    @Column(name = "is_active")
    private boolean isActive = true;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * contentColumnsJson을 String[]로 변환 (쉼표 구분).
     */
    public String[] getContentColumns() {
        if (contentColumnsJson == null || contentColumnsJson.isBlank()) return new String[0];
        return contentColumnsJson.split(",");
    }

    public void setContentColumns(String[] columns) {
        this.contentColumnsJson = columns == null ? null : String.join(",", columns);
    }

    /**
     * metadataColumnsJson을 String[]로 변환.
     */
    public String[] getMetadataColumns() {
        if (metadataColumnsJson == null || metadataColumnsJson.isBlank()) return new String[0];
        return metadataColumnsJson.split(",");
    }

    public void setMetadataColumns(String[] columns) {
        this.metadataColumnsJson = columns == null ? null : String.join(",", columns);
    }
}
