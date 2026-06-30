package com.ragvault.core.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * sql_table_config 엔티티.
 * SQL 경로에서 허용된 고객사 MySQL 테이블 화이트리스트.
 *
 * allowed_columns / excluded_columns 으로 컬럼 접근 제어 (Layer 1).
 *
 * 멀티 데이터소스 전제: (datasource_id, source_table) 복합 UNIQUE.
 */
@Entity
@Table(name = "sql_table_config",
        uniqueConstraints = @UniqueConstraint(columnNames = {"datasource_id", "source_table"}))
@Getter
@Setter
@NoArgsConstructor
public class SqlTableConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "datasource_id")  // null = legacy 단일 datasource fallback
    private Integer datasourceId;

    @Column(name = "source_table", nullable = false, length = 100)
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

    /**
     * sample_queries (jsonb) — JSON 배열·객체·문자열 모두 String 으로 수신.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sample_queries", columnDefinition = "jsonb")
    @JsonDeserialize(using = SqlTableConfig.JsonNodeToStringDeserializer.class)
    private String sampleQueries;

    /** JSON 배열·객체 → String 변환 Deserializer (API 요청 호환성) */
    static class JsonNodeToStringDeserializer extends StdDeserializer<String> {
        JsonNodeToStringDeserializer() { super(String.class); }

        @Override
        public String deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node.isTextual()) return node.asText();
            return node.toString();
        }
    }

    @Column(name = "data_sensitivity", nullable = false, length = 20)
    private String dataSensitivity = "internal";

    @Column(name = "allowed_groups", columnDefinition = "TEXT[]", nullable = false)
    private String[] allowedGroups = new String[]{"all"};

    @Column(name = "llm_status", length = 20)
    private String llmStatus = "done";

    @JsonProperty("isActive")
    @Column(name = "is_active")
    private boolean isActive = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
