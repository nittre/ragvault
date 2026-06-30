package com.ragvault.core.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * document_chunks 테이블 엔티티 (M2 스펙).
 *
 * embedding(vector(1024))은 JPA 직접 매핑 불가 — native query에서만 처리.
 * access_groups(TEXT[])도 JPA 매핑 제외 — native query에서 처리 (ADR-0002).
 *
 * LL-0006: pgvector 전용 컬럼은 @Transient 또는 필드 제외.
 */
@Entity
@Table(name = "document_chunks")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_table", nullable = false, length = 100)
    private String sourceTable;

    @Column(name = "source_id", nullable = false, length = 200)
    private String sourceId;

    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    // embedding(vector(1024)) — JPA 매핑 제외, native query에서 처리

    @Column(name = "embedding_model", nullable = false, length = 100)
    private String embeddingModel;

    @Column(name = "tokenizer_model", nullable = false, length = 100)
    private String tokenizerModel;

    @Column(columnDefinition = "jsonb")
    private String metadata;

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
     * pgvector 검색 결과 투영 DTO.
     */
    public record ChunkResult(String content, String sourceTable, String sourceId, double score) {}
}
