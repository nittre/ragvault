package com.ragservice.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only JDBC 연결로 고객사 MySQL SELECT 를 실행하는 서비스.
 *
 * - conn.setReadOnly(true): DML 추가 방어
 * - setQueryTimeout(10초): 장기 쿼리 차단
 * - setMaxRows(1000): 결과 행 제한
 * - LIMIT 미포함 SQL 에 자동으로 "LIMIT 1000" 추가
 * - DriverManager 직접 사용 — 시스템 PostgreSQL DataSource 와 분리
 *
 * requirements/08-text-to-sql.md
 * ADR-0007: SQL PII 마스킹 Layer 1 (컬럼 접근 제어는 SqlValidator 에서 처리)
 */
@Slf4j
@Service
public class SqlExecutorService {

    @Value("${rag.mysql.host}")
    private String host;

    @Value("${rag.mysql.port:3306}")
    private int port;

    @Value("${rag.mysql.database}")
    private String database;

    @Value("${rag.mysql.username}")
    private String username;

    @Value("${rag.mysql.password}")
    private String password;

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
     * @param sql 검증된 SELECT SQL
     * @return SqlResult — hasError() = true 면 실패
     */
    public SqlResult execute(String sql) {
        String safeSql = ensureLimit(sql);

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?connectTimeout=5000&socketTimeout=" + (QUERY_TIMEOUT_SEC * 1000);

        long start = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            conn.setReadOnly(true);
            try (PreparedStatement ps = conn.prepareStatement(safeSql)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SEC);
                ps.setMaxRows(MAX_ROWS);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> rows = mapResultSet(rs);
                    long elapsed = System.currentTimeMillis() - start;
                    log.debug("SQL executed in {}ms, rows={}", elapsed, rows.size());
                    return SqlResult.success(rows, elapsed);
                }
            }
        } catch (SQLTimeoutException e) {
            log.warn("SQL timeout after {}s", QUERY_TIMEOUT_SEC);
            return SqlResult.error("쿼리 타임아웃 (" + QUERY_TIMEOUT_SEC + "초 초과)");
        } catch (SQLException e) {
            log.error("SQL execution error: {}", e.getMessage());
            return SqlResult.error("쿼리 실행 오류: " + e.getMessage());
        }
    }

    /**
     * LIMIT 절이 없는 SQL 에 "LIMIT {MAX_ROWS}" 를 추가.
     */
    private String ensureLimit(String sql) {
        // LIMIT 키워드는 줄바꿈·탭 등 어떤 공백으로 둘러싸여도 감지해야 한다.
        // (LLM 이 "...\nLIMIT 5" 처럼 생성하면 기존 " LIMIT " 검사는 놓쳐 중복 LIMIT 가 붙는다)
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
