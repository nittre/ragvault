package com.ragservice.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragservice.rag.domain.SqlTableConfig;
import com.ragservice.rag.repository.SqlTableConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.Duration;
import java.util.*;

/**
 * 고객사 MySQL INFORMATION_SCHEMA 조회 서비스.
 *
 * - sql_table_config.is_active = true 인 테이블만 조회
 * - excluded_columns / allowed_columns 필터 적용 (ADR-0007 Layer 1)
 * - Redis 1h 캐시 (key: "schema:customer")
 * - DriverManager 직접 사용 — 별도 DataSource 빈 불필요
 *   (rag.mysql.* 설정 = 고객사 MySQL, 시스템 PostgreSQL DataSource 와 분리)
 *
 * requirements/08-text-to-sql.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaInspectorService {

    @Value("${rag.mysql.host}")
    private String mysqlHost;

    @Value("${rag.mysql.port:3306}")
    private int mysqlPort;

    @Value("${rag.mysql.database}")
    private String mysqlDatabase;

    @Value("${rag.mysql.username}")
    private String mysqlUsername;

    @Value("${rag.mysql.password}")
    private String mysqlPassword;

    private final SqlTableConfigRepository sqlTableConfigRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String SCHEMA_CACHE_KEY = "schema:customer";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    /**
     * 활성 테이블 스키마 반환. Redis 1h 캐시.
     *
     * @return Map<테이블명, 컬럼 정보 리스트> — 빈 맵 = SQL 경로 불가
     */
    public Map<String, List<ColumnInfo>> getSchemaForActiveTables() {
        String cached = redisTemplate.opsForValue().get(SCHEMA_CACHE_KEY);
        if (cached != null) {
            try {
                TypeReference<Map<String, List<ColumnInfo>>> typeRef = new TypeReference<>() {};
                return objectMapper.readValue(cached, typeRef);
            } catch (Exception e) {
                log.warn("Schema cache parse error, refetching", e);
            }
        }

        List<SqlTableConfig> activeTables = sqlTableConfigRepository.findByIsActiveTrue();
        if (activeTables.isEmpty()) {
            log.info("No active sql_table_config entries found");
            return Map.of();
        }

        Map<String, List<ColumnInfo>> schema = fetchFromInformationSchema(activeTables);

        try {
            String json = objectMapper.writeValueAsString(schema);
            redisTemplate.opsForValue().set(SCHEMA_CACHE_KEY, json, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Schema cache write error (non-fatal)", e);
        }

        return schema;
    }

    /**
     * 스키마 캐시 강제 삭제 (admin refresh 용).
     */
    public void evictSchemaCache() {
        redisTemplate.delete(SCHEMA_CACHE_KEY);
        log.info("Schema cache evicted");
    }

    private Map<String, List<ColumnInfo>> fetchFromInformationSchema(List<SqlTableConfig> activeTables) {
        Map<String, List<ColumnInfo>> result = new LinkedHashMap<>();

        String url = "jdbc:mysql://" + mysqlHost + ":" + mysqlPort + "/" + mysqlDatabase
                + "?connectTimeout=5000&socketTimeout=10000";

        try (Connection conn = DriverManager.getConnection(url, mysqlUsername, mysqlPassword)) {
            for (SqlTableConfig config : activeTables) {
                String tableName = config.getSourceTable();
                List<ColumnInfo> columns = new ArrayList<>();

                String sql = """
                        SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_COMMENT
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                        ORDER BY ORDINAL_POSITION
                        """;

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, mysqlDatabase);
                    ps.setString(2, tableName);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String colName = rs.getString("COLUMN_NAME");

                            // ADR-0007 Layer 1: excluded_columns 필터
                            if (isExcluded(colName, config.getExcludedColumns())) continue;
                            // allowed_columns 필터 (null/empty = 전체 허용)
                            if (!isAllowed(colName, config.getAllowedColumns())) continue;

                            columns.add(new ColumnInfo(
                                    colName,
                                    rs.getString("DATA_TYPE"),
                                    "YES".equals(rs.getString("IS_NULLABLE")),
                                    rs.getString("COLUMN_COMMENT")
                            ));
                        }
                    }
                }
                result.put(tableName, columns);
            }
        } catch (SQLException e) {
            log.error("INFORMATION_SCHEMA query failed: {}", e.getMessage());
            // 빈 맵 반환 — SQL 경로에서 "테이블 없음" 처리
        }

        return result;
    }

    private boolean isExcluded(String col, String[] excluded) {
        if (excluded == null) return false;
        for (String ex : excluded) {
            if (ex.equalsIgnoreCase(col)) return true;
        }
        return false;
    }

    private boolean isAllowed(String col, String[] allowed) {
        if (allowed == null || allowed.length == 0) return true; // null = 전체 허용
        for (String al : allowed) {
            if (al.equalsIgnoreCase(col)) return true;
        }
        return false;
    }

    /**
     * Admin UI용: 고객사 MySQL의 모든 BASE TABLE 목록과 컬럼 정보를 반환.
     * (sql_table_config 등록 여부와 무관하게 전체 테이블 탐색)
     *
     * - TABLE_TYPE = 'BASE TABLE' 만 포함 (VIEW 제외)
     * - 컬럼별 PK 여부(COLUMN_KEY='PRI') 포함
     * - 테이블 코멘트(TABLE_COMMENT) 포함
     */
    public List<TableInfo> getAllTablesWithSchema() {
        String url = "jdbc:mysql://" + mysqlHost + ":" + mysqlPort + "/" + mysqlDatabase
                + "?connectTimeout=5000&socketTimeout=10000";
        List<TableInfo> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, mysqlUsername, mysqlPassword)) {

            // 1) 전체 BASE TABLE 목록 + 테이블 코멘트
            String tablesSql = """
                    SELECT TABLE_NAME, IFNULL(TABLE_COMMENT, '') AS TABLE_COMMENT
                    FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'
                    ORDER BY TABLE_NAME
                    """;
            List<String[]> tables = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(tablesSql)) {
                ps.setString(1, mysqlDatabase);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tables.add(new String[]{rs.getString("TABLE_NAME"), rs.getString("TABLE_COMMENT")});
                    }
                }
            }

            // 2) 테이블별 컬럼 상세 (PK 포함)
            String colsSql = """
                    SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE,
                           IFNULL(COLUMN_COMMENT, '') AS COLUMN_COMMENT,
                           IF(COLUMN_KEY = 'PRI', 1, 0) AS IS_PK
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                    ORDER BY ORDINAL_POSITION
                    """;
            for (String[] t : tables) {
                List<ColumnDetail> cols = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(colsSql)) {
                    ps.setString(1, mysqlDatabase);
                    ps.setString(2, t[0]);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            cols.add(new ColumnDetail(
                                    rs.getString("COLUMN_NAME"),
                                    rs.getString("DATA_TYPE"),
                                    "YES".equals(rs.getString("IS_NULLABLE")),
                                    rs.getString("COLUMN_COMMENT"),
                                    rs.getInt("IS_PK") == 1
                            ));
                        }
                    }
                }
                result.add(new TableInfo(t[0], t[1], cols));
            }
        } catch (SQLException e) {
            log.error("getAllTablesWithSchema failed: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 단일 컬럼 메타데이터 (기존 — SQL 경로 스키마 캐시용).
     */
    public record ColumnInfo(String name, String dataType, boolean nullable, String comment) {}

    /** Admin 스키마 탐색용 — 테이블 전체 정보. */
    public record TableInfo(String tableName, String tableComment, List<ColumnDetail> columns) {}

    /** Admin 스키마 탐색용 — 컬럼 상세 (PK 포함). */
    public record ColumnDetail(String name, String dataType, boolean nullable, String comment, boolean primaryKey) {}
}
