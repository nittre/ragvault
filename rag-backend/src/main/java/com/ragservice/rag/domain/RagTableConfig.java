package com.ragservice.rag.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * rag_table_config 테이블 엔티티.
 *
 * RAG 대상 MySQL 테이블 동적 관리.
 * data_sensitivity='restricted' → Phase 0 거부 (ADR-0002).
 */
@Entity
@Table(name = "rag_table_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagTableConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "source_table", nullable = false, unique = true, length = 100)
    private String sourceTable;

    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @Column(name = "chunking_strategy", nullable = false, length = 50)
    private String chunkingStrategy = "recursive";

    @Column(name = "chunk_size", nullable = false)
    private int chunkSize = 500;

    @Column(name = "chunk_overlap", nullable = false)
    private int chunkOverlap = 50;

    @Column(name = "title_column", length = 100)
    private String titleColumn;

    /**
     * content_columns (TEXT[]) — PostgreSQL 배열.
     * JPA는 String[]를 TEXT[]로 직접 지원하지 않으므로
     * native query 또는 @Column(columnDefinition)으로 처리.
     * Hibernate 6.x에서 @JdbcTypeCode(SqlTypes.ARRAY) 또는
     * AttributeConverter 사용 가능하나 H2 테스트 호환성을 위해
     * 별도 변환 없이 TEXT 컬럼으로 fallback.
     */
    @Column(name = "content_columns", columnDefinition = "TEXT")
    private String contentColumnsJson;

    @Column(name = "metadata_columns", columnDefinition = "TEXT")
    private String metadataColumnsJson;

    @Column(name = "pk_column", nullable = false, length = 100)
    private String pkColumn = "id";

    @Column(name = "pii_masking_level", length = 20)
    private String piiMaskingLevel = "standard";

    @Column(name = "data_sensitivity", nullable = false, length = 20)
    private String dataSensitivity = "internal";

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
