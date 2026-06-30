package com.ragvault.core.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * routing_embedding 엔티티 — 데이터소스/테이블 설명 임베딩(라우팅 후보 좁히기용).
 *
 * embedding(vector(1024))은 JPA 매핑 제외 — native query 에서만 처리 (LL-0006).
 */
@Entity
@Table(name = "routing_embedding")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutingEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "datasource_id", nullable = false)
    private Integer datasourceId;

    @Column(name = "source_table", length = 100)
    private String sourceTable;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    // embedding(vector(1024)) — JPA 매핑 제외, native query 에서 처리

    @Column(name = "updated_at")
    private Instant updatedAt;
}
