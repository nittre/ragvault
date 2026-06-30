package com.ragvault.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only JDBC 연결로 고객사 MySQL/MariaDB SELECT 를 실행하는 서비스.
 *
 * - conn.setReadOnly(true): DML 추가 방어
 * - setQueryTimeout(10초): 장기 쿼리 차단
 * - setMaxRows(1000): 결과 행 제한
 * - LIMIT 미포함 SQL 에 자동으로 "LIMIT 1000" 추가
 * - DriverManager 직접 사용 — 시스템 PostgreSQL DataSource 와 분리
 * - 연결 정보는 datasource_config 테이블에서 조회 (환경변수 fallback 없음)
 *
 * requirements/08-text-to-sql.md
 * ADR-0007: SQL PII 마스킹 Layer 1 (컬럼 접근 제어는 SqlValidator 에서 처리)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlExecutorService {

    private final DataSourceConfigService dataSourceConfigService;

    private static final int QUERY_TIMEOUT_SEC = 10;
    private static final int MAX_ROWS = 1000;

    public record SqlResult(List<Map<String, Object>> rows, int rowCount, long elapsedMs, String error) {

        static SqlResult success(List<Map<String, Object>> rows, long elapsed) {
            return new SqlResult(rows, rows.size(), elapsed, null);
        }

        static SqlResult error(String error) {
            return new SqlResult(List.of(), 0, 0, error);
        }

        public boolean hasError() {
            return error != null;
        }
    }

    /**
     * SQL 을 실행하고 결과를 반환.
     * LIMIT 미포함 시 자동 추가. Read-only 연결.
     *
     * @param sql          검증된 SELECT SQL
     * @param datasourceId datasource_config 조회용 ID (필수)
     * @return SqlResult — hasError() = true 면 실패
     */
    public SqlResult execute(String sql, Integer datasourceId) {
        String safeSql = ensureLimit(sql);

        var ds = dataSourceConfigService.findById(datasourceId);
        String url = dataSourceConfigService.buildJdbcUrl(ds);
        String user = ds.getUsername();
        String pass = dataSourceConfigService.getDecryptedPassword(ds);

        long start = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            conn.setReadOnly(true);
            try (PreparedStatement ps = conn.prepareStatement(safeSql)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
                ps.setMaxRows(MAX_ROWS);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> rows = mapResultSet(rs);
                    long elapsed = System.currentTimeMillis() - start;
                    log.debug("SQL executed in {}ms, rows={}, datasourceId={}", elapsed, rows.size(), datasourceId);
                    return SqlResult.success(rows, elapsed);
                }
            }
        } catch (SQLTimeoutException e) {
            log.warn("SQL timeout after {}s, datasourceId={}", QUERY_TIMEOUT_SEC, datasourceId);
            return SqlResult.error("쿼리 타임아웃 (" + QUERY_TIMEOUT_SEC + "초 초과)");
        } catch (SQLException e) {
            log.error("SQL execution error: datasourceId={}, error={}", datasourceId, e.getMessage());
            return SqlResult.error("쿼리 실행 오류: " + e.getMessage());
        }
    }

    public record DryRunResult(boolean ok, String error) {
        static DryRunResult pass() {
            return new DryRunResult(true, null);
        }

        static DryRunResult fail(String error) {
            return new DryRunResult(false, error);
        }
    }

    /**
     * EXPLAIN dry-run — SQL 을 실행하지 않고 워런하우스(MySQL)에 이름 해석을 떠넘긴다.
     * 파서만으로는 CTE·JOIN 별칭이 어느 컬럼을 가리키는지 확정하기 어려우므로,
     * 실행 직전 EXPLAIN 으로 별칭/컬럼 해석 오류(Unknown column 등)를 미리 잡는다.
     * EXPLAIN 은 행을 반환하지 않으므로 저비용·안전.
     *
     * @param sql          검증을 통과한 SELECT SQL
     * @param datasourceId datasource_config 조회용 ID (필수)
     * @return DryRunResult — ok()=false 면 error 에 MySQL 오류 메시지 포함
     */
    public DryRunResult explain(String sql, Integer datasourceId) {
        var ds = dataSourceConfigService.findById(datasourceId);
        String url = dataSourceConfigService.buildJdbcUrl(ds);
        String user = ds.getUsername();
        String pass = dataSourceConfigService.getDecryptedPassword(ds);

        String explainSql = "EXPLAIN " + sql.trim().replaceAll(";\\s*$", "");
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            conn.setReadOnly(true);
            try (PreparedStatement ps = conn.prepareStatement(explainSql)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
                ps.executeQuery();
                return DryRunResult.pass();
            }
        } catch (SQLTimeoutException e) {
            log.warn("EXPLAIN dry-run timeout, datasourceId={}", datasourceId);
            return DryRunResult.fail("쿼리 분석 타임아웃");
        } catch (SQLException e) {
            log.debug("EXPLAIN dry-run rejected: datasourceId={}, error={}", datasourceId, e.getMessage());
            return DryRunResult.fail(e.getMessage());
        }
    }

    /**
     * LIMIT 절이 없는 SQL 에 "LIMIT {MAX_ROWS}" 를 추가.
     */
    private String ensureLimit(String sql) {
        if (sql.toUpperCase().matches("(?s).*\\bLIMIT\\b.*")) return sql;
        return sql.trim().replaceAll(";\\s*$", "") + " LIMIT " + MAX_ROWS;
    }

    private List<Map<String, Object>> mapResultSet(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= cols; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }
}
