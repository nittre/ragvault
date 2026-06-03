package com.ragservice.rag.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * sql_table_config 엔티티.
 * SQL 경로에서 허용된 고객사 MySQL 테이블 화이트리스트.
 *
 * ADR-0007 Layer 1: allowed_columns / excluded_columns 으로 컬럼 접근 제어.
 * ADR-0002: allowed_groups 필터 (Phase 0 = ['all']).
 */
@Entity
@Table(name = "sql_table_config")
@Getter
@Setter
@NoArgsConstructor
public class SqlTableConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "source_table", nullable = false, unique = true, length = 100)
    private String sourceTable;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "allowed_columns", columnDefinition = "TEXT[]")
    private String[] allowedColumns;

    @Column(name = "excluded_columns", columnDefinition = "TEXT[]")
    private String[] excludedColumns;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String relationships;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sample_queries", columnDefinition = "jsonb")
    private String sampleQueries;

    @Column(name = "data_sensitivity", nullable = false, length = 20)
    private String dataSensitivity = "internal";

    @Column(name = "allowed_groups", columnDefinition = "TEXT[]", nullable = false)
    private String[] allowedGroups = new String[]{"all"};

    @Column(name = "is_active")
    private boolean isActive = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
