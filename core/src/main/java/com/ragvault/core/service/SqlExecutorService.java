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

    /** 허용 가능한 최대치 — 상위 계층 검증을 거치지 않은 호출자가 있어도 이 범위 밖으로 못 나가게 하는 안전망. */
    private static final int MAX_ALLOWED_TIMEOUT_SEC = 300;
    private static final int MAX_ALLOWED_ROWS = 5000;

    /**
     * SQL 을 실행하고 결과를 반환.
     * LIMIT 미포함 시 자동 추가. Read-only 연결.
     * 하드코딩된 기본 타임아웃(10초)/최대 행수(1000행)를 사용 — 위젯 서비스 등 기존 호출자와 하위호환.
     *
     * @param sql          검증된 SELECT SQL
     * @param datasourceId datasource_config 조회용 ID (필수)
     * @return SqlResult — hasError() = true 면 실패
     */
    public SqlResult execute(String sql, Integer datasourceId) {
        return execute(sql, datasourceId, QUERY_TIMEOUT_SEC, MAX_ROWS);
    }

    /**
     * SQL 을 실행하고 결과를 반환 — 타임아웃/최대 행수를 호출자가 지정 (ADR-0005 파라미터 튜닝).
     * LIMIT 미포함 시 자동 추가. Read-only 연결.
     *
     * @param sql          검증된 SELECT SQL
     * @param datasourceId datasource_config 조회용 ID (필수)
     * @param queryTimeoutSec 쿼리 타임아웃(초) — 상위 계층 검증과 무관하게 [1, 300] 범위로 재클램핑
     * @param maxRows      최대 결과 행수 — 상위 계층 검증과 무관하게 [1, 5000] 범위로 재클램핑
     * @return SqlResult — hasError() = true 면 실패
     */
    public SqlResult execute(String sql, Integer datasourceId, int queryTimeoutSec, int maxRows) {
        int safeTimeoutSec = Math.max(1, Math.min(queryTimeoutSec, MAX_ALLOWED_TIMEOUT_SEC));
        int safeMaxRows = Math.max(1, Math.min(maxRows, MAX_ALLOWED_ROWS));
        String safeSql = ensureLimit(sql, safeMaxRows);

        var ds = dataSourceConfigService.findById(datasourceId);
        String url = dataSourceConfigService.buildJdbcUrl(ds);
        String user = ds.getUsername();
        String pass = dataSourceConfigService.getDecryptedPassword(ds);

        long start = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            conn.setReadOnly(true);
            try (PreparedStatement ps = conn.prepareStatement(safeSql)) {
                ps.setQueryTimeout(safeTimeoutSec);
                ps.setMaxRows(safeMaxRows);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> rows = mapResultSet(rs);
                    long elapsed = System.currentTimeMillis() - start;
                    log.debug("SQL executed in {}ms, rows={}, datasourceId={}", elapsed, rows.size(), datasourceId);
                    return SqlResult.success(rows, elapsed);
                }
            }
        } catch (SQLTimeoutException e) {
            log.warn("SQL timeout after {}s, datasourceId={}", safeTimeoutSec, datasourceId);
            return SqlResult.error("쿼리 타임아웃 (" + safeTimeoutSec + "초 초과)");
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
     * LIMIT 절이 없는 SQL 에 "LIMIT {maxRows}" 를 추가.
     * maxRows 는 항상 execute() 에서 int로 클램핑된 값만 전달되므로 문자열 결합에 안전하다.
     */
    private String ensureLimit(String sql, int maxRows) {
        if (sql.toUpperCase().matches("(?s).*\\bLIMIT\\b.*")) return sql;
        return sql.trim().replaceAll(";\\s*$", "") + " LIMIT " + maxRows;
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
