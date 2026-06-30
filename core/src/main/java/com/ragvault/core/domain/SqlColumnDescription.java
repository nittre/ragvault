package com.ragvault.core.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * sql_column_description 엔티티 — 컬럼 자연어 설명(구조화 저장).
 *
 * SQL 생성 시 스키마 블록에 인라인 주입된다(top-k 검색 아님).
 * source: 'comment'(DB 코멘트 시드) | 'llm'(자동 생성) | 'human'(어드민 수정).
 */
@Entity
@Table(name = "sql_column_description",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"datasource_id", "source_table", "column_name"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SqlColumnDescription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "datasource_id", nullable = false)
    private Integer datasourceId;

    @Column(name = "source_table", nullable = false, length = 100)
    private String sourceTable;

    @Column(name = "column_name", nullable = false, length = 100)
    private String columnName;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "source", nullable = false, length = 20)
    private String source = "llm";

    @Column(name = "updated_at")
    private Instant updatedAt;
}
