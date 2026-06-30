package com.ragvault.core.service;

import com.ragvault.core.domain.SqlColumnDescription;
import com.ragvault.core.domain.SqlTableConfig;
import com.ragvault.core.repository.SqlColumnDescriptionRepository;
import com.ragvault.core.repository.SqlTableConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 고객사 MySQL/MariaDB INFORMATION_SCHEMA 조회 서비스.
 *
 * - sql_table_config.is_active = true 인 테이블만 조회
 * - excluded_columns / allowed_columns 필터 적용 (ADR-0007 Layer 1)
 * - 인메모리 캐시 (dsId → schema / FK)
 * - 외부 DB 연결은 DataSourceConfigService.openConnection 재사용
 * - MySQL/MariaDB INFORMATION_SCHEMA 방언 동일 — 쿼리 변경 불필요
 *
 * requirements/08-text-to-sql.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaInspectorService {

    private final SqlTableConfigRepository sqlTableConfigRepository;
    private final SqlColumnDescriptionRepository columnDescriptionRepository;
    private final DataSourceConfigService dataSourceConfigService;

    // 인메모리 캐시 (dsId 를 키로 직접 사용)
    private final Map<Integer, Map<String, List<ColumnInfo>>> schemaCache = new ConcurrentHashMap<>();
    private final Map<Integer, List<FkInfo>> fkCache = new ConcurrentHashMap<>();

    /**
     * 활성 테이블 스키마 반환. 인메모리 캐시.
     *
     * @param datasourceId datasource_config 조회용 ID
     * @return Map<테이블명, 컬럼 정보 리스트> — 빈 맵 = SQL 경로 불가
     */
    public Map<String, List<ColumnInfo>> getSchemaForActiveTables(Integer datasourceId) {
        Map<String, List<ColumnInfo>> cached = schemaCache.get(datasourceId);
        if (cached != null) return cached;

        List<SqlTableConfig> activeTables =
                sqlTableConfigRepository.findByDatasourceIdAndIsActiveTrue(datasourceId);

        if (activeTables.isEmpty()) {
            log.info("No active sql_table_config entries found for datasourceId={}", datasourceId);
            return Map.of();
        }

        Map<String, List<ColumnInfo>> schema = fetchFromInformationSchema(datasourceId, activeTables);

        // 어드민/LLM 이 작성한 컬럼 설명을 COMMENT 위에 덮어씌운다(인라인 주입용).
        applyColumnDescriptions(datasourceId, schema);

        schemaCache.put(datasourceId, schema);
        return schema;
    }

    /**
     * 스키마 캐시 강제 삭제.
     *
     * @param datasourceId 캐시를 삭제할 datasource ID
     */
    public void evictSchemaCache(Integer datasourceId) {
        schemaCache.remove(datasourceId);
        log.info("Schema cache evicted: dsId={}", datasourceId);
    }

    /**
     * 활성 테이블의 FK 관계 반환. 인메모리 캐시.
     * SQL 생성기 프롬프트에 테이블 관계를 제공해 JOIN 컬럼 추측 오류를 방지한다.
     *
     * @param datasourceId datasource ID
     * @return FK 관계 목록 — 활성 테이블 범위 내의 FK만 반환
     */
    public List<FkInfo> getForeignKeysForActiveTables(Integer datasourceId) {
        List<FkInfo> cached = fkCache.get(datasourceId);
        if (cached != null) return cached;

        List<SqlTableConfig> activeTables =
                sqlTableConfigRepository.findByDatasourceIdAndIsActiveTrue(datasourceId);
        if (activeTables.isEmpty()) {
            return List.of();
        }

        List<String> tableNames = activeTables.stream()
                .map(SqlTableConfig::getSourceTable)
                .toList();

        List<FkInfo> fkList = fetchForeignKeys(datasourceId, tableNames);
        fkCache.put(datasourceId, fkList);
        return fkList;
    }

    /**
     * FK 캐시 강제 삭제.
     */
    public void evictFkCache(Integer datasourceId) {
        fkCache.remove(datasourceId);
        log.info("FK cache evicted: dsId={}", datasourceId);
    }

    /**
     * Admin UI용: 특정 datasource 의 모든 BASE TABLE 목록과 컬럼 정보를 반환.
     */
    public List<TableInfo> getAllTablesWithSchema(Integer datasourceId) {
        String dbName = dataSourceConfigService.findById(datasourceId).getDbName();
        List<TableInfo> result = new ArrayList<>();
        try (Connection conn = openConnection(datasourceId)) {

            String tablesSql = """
                    SELECT TABLE_NAME, IFNULL(TABLE_COMMENT, '') AS TABLE_COMMENT
                    FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'
                    ORDER BY TABLE_NAME
                    """;
            List<String[]> tables = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(tablesSql)) {
                ps.setString(1, dbName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tables.add(new String[]{rs.getString("TABLE_NAME"), rs.getString("TABLE_COMMENT")});
                    }
                }
            }

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
                    ps.setString(1, dbName);
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
        } catch (Exception e) {
            log.error("getAllTablesWithSchema failed: datasourceId={}, error={}", datasourceId, e.getMessage());
        }
        return result;
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private Connection openConnection(Integer datasourceId) throws Exception {
        return dataSourceConfigService.openConnection(dataSourceConfigService.findById(datasourceId));
    }

    /**
     * sql_column_description 의 설명을 schema 의 ColumnInfo.comment 에 덮어쓴다.
     * 저장된 설명이 있으면 우선, 없으면 기존 COMMENT 유지. SQL 생성 프롬프트 인라인 주입용.
     */
    private void applyColumnDescriptions(Integer datasourceId, Map<String, List<ColumnInfo>> schema) {
        List<SqlColumnDescription> descs = columnDescriptionRepository.findByDatasourceId(datasourceId);
        if (descs.isEmpty()) return;

        // (table -> (column -> description))
        Map<String, Map<String, String>> byTable = new HashMap<>();
        for (SqlColumnDescription d : descs) {
            byTable.computeIfAbsent(d.getSourceTable(), k -> new HashMap<>())
                    .put(d.getColumnName(), d.getDescription());
        }

        schema.forEach((table, cols) -> {
            Map<String, String> colDescs = byTable.get(table);
            if (colDescs == null) return;
            cols.replaceAll(c -> {
                String desc = colDescs.get(c.name());
                return (desc == null || desc.isBlank())
                        ? c
                        : new ColumnInfo(c.name(), c.dataType(), c.nullable(), desc);
            });
        });
    }

    private Map<String, List<ColumnInfo>> fetchFromInformationSchema(
            Integer datasourceId, List<SqlTableConfig> activeTables) {
        Map<String, List<ColumnInfo>> result = new LinkedHashMap<>();
        String dbName = dataSourceConfigService.findById(datasourceId).getDbName();

        try (Connection conn = openConnection(datasourceId)) {
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
                    ps.setString(1, dbName);
                    ps.setString(2, tableName);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String colName = rs.getString("COLUMN_NAME");
                            if (isExcluded(colName, config.getExcludedColumns())) continue;
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
        } catch (Exception e) {
            log.error("INFORMATION_SCHEMA query failed: {}", e.getMessage());
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
        if (allowed == null || allowed.length == 0) return true;
        for (String al : allowed) {
            if (al.equalsIgnoreCase(col)) return true;
        }
        return false;
    }

    private List<FkInfo> fetchForeignKeys(Integer datasourceId, List<String> tableNames) {
        List<FkInfo> result = new ArrayList<>();
        if (tableNames.isEmpty()) return result;
        String dbName = dataSourceConfigService.findById(datasourceId).getDbName();

        String placeholders = tableNames.stream().map(t -> "?").collect(java.util.stream.Collectors.joining(", "));
        String sql = """
                SELECT kcu.TABLE_NAME, kcu.COLUMN_NAME,
                       kcu.REFERENCED_TABLE_NAME, kcu.REFERENCED_COLUMN_NAME
                FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                WHERE kcu.TABLE_SCHEMA = ?
                  AND kcu.REFERENCED_TABLE_NAME IS NOT NULL
                  AND kcu.TABLE_NAME IN (""" + placeholders + """
                )
                ORDER BY kcu.TABLE_NAME, kcu.COLUMN_NAME
                """;

        try (Connection conn = openConnection(datasourceId);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dbName);
            for (int i = 0; i < tableNames.size(); i++) {
                ps.setString(i + 2, tableNames.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new FkInfo(
                            rs.getString("TABLE_NAME"),
                            rs.getString("COLUMN_NAME"),
                            rs.getString("REFERENCED_TABLE_NAME"),
                            rs.getString("REFERENCED_COLUMN_NAME")
                    ));
                }
            }
        } catch (Exception e) {
            log.error("FK query failed: datasource={}, error={}", dbName, e.getMessage());
        }
        return result;
    }

    // ── inner types ──────────────────────────────────────────────────────────

    public record ColumnInfo(String name, String dataType, boolean nullable, String comment) {}

    public record FkInfo(String tableName, String columnName, String referencedTable, String referencedColumn) {}

    public record TableInfo(String tableName, String tableComment, List<ColumnDetail> columns) {}

    public record ColumnDetail(String name, String dataType, boolean nullable, String comment, boolean primaryKey) {}
}
