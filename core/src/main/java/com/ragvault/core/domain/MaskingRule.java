package com.ragvault.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * masking_rule 엔티티.
 * PII 마스킹 규칙 (정규식 + 치환토큰)을 DB 로 관리.
 *
 * ADR-0007: SQL 결과 PII 마스킹.
 * ADR-0008: 모든 LLM 응답 경로 PII 마스킹.
 * level: standard(기본 적용) / aggressive(추가 적용).
 */
@Entity
@Table(name = "masking_rule")
@Getter
@Setter
@NoArgsConstructor
public class MaskingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "datasource_id")
    private Integer datasourceId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String pattern;

    @Column(nullable = false, length = 100)
    private String replacement;

    @Column(nullable = false, length = 20)
    private String level = "standard";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
