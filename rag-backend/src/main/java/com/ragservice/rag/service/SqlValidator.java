package com.ragservice.rag.service;

import com.ragservice.rag.domain.SqlTableConfig;
import com.ragservice.rag.repository.SqlTableConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ADR-0007 Layer 1: JSqlParser 기반 SQL AST 검증.
 *
 * 검증 규칙:
 * 1. SELECT 전용 (DML/DDL 차단)
 * 2. SELECT * 거부
 * 3. 테이블 화이트리스트 검증 (sql_table_config.is_active=true)
 * 4. excluded_columns 사용 거부
 *
 * NOTE: pgvector 전용 문법(<=> 등)은 SqlValidator 대상이 아님.
 * SqlValidator 는 고객사 MySQL SELECT 만 다룬다 (LL-0006 참조).
 *
 * requirements/08-text-to-sql.md
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlValidator {

    private final SqlTableConfigRepository sqlTableConfigRepository;

    public record ValidationResult(boolean allowed, String reason) {
        static ValidationResult allow() {
            return new ValidationResult(true, null);
        }

        static ValidationResult deny(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    /**
     * SQL 문자열을 검증해 허용/거부 여부를 반환.
     *
     * @param sql 검증할 SQL 문자열
     * @return ValidationResult — allowed=false 시 reason 포함
     */
    public ValidationResult validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return ValidationResult.deny("SQL이 비어 있습니다");
        }

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            log.warn("SQL parse error: {}", e.getMessage());
            return ValidationResult.deny("SQL 구문 오류: " + e.getMessage());
        }

        // 1. SELECT 전용 검사
        if (!(statement instanceof Select select)) {
            return ValidationResult.deny("SELECT 구문만 허용됩니다");
        }

        // 2. SELECT * 검사 (중첩 서브쿼리 포함)
        if (hasSelectStar(select)) {
            return ValidationResult.deny("SELECT * 는 허용되지 않습니다. 명시적 컬럼 목록을 사용하세요");
        }

        // 3. 테이블 화이트리스트 검사
        Set<String> tables = extractTables(select);
        List<SqlTableConfig> activeConfigs = sqlTableConfigRepository.findByIsActiveTrue();
        Set<String> allowedTables = activeConfigs.stream()
                .map(c -> c.getSourceTable().toLowerCase())
                .collect(Collectors.toSet());

        for (String table : tables) {
            if (!allowedTables.contains(table.toLowerCase())) {
                return ValidationResult.deny("허용되지 않은 테이블: " + table);
            }
        }

        // 4. excluded_columns 사용 검사
        Set<String> usedColumns = extractColumns(select);
        for (SqlTableConfig config : activeConfigs) {
            if (!tables.contains(config.getSourceTable().toLowerCase())) continue;
            if (config.getExcludedColumns() == null) continue;
            for (String excluded : config.getExcludedColumns()) {
                if (usedColumns.stream().anyMatch(c -> c.equalsIgnoreCase(excluded))) {
                    return ValidationResult.deny("허용되지 않은 컬럼 접근: " + excluded);
                }
            }
        }

        return ValidationResult.allow();
    }

    /**
     * SELECT * 또는 table.* 사용 여부를 재귀적으로 검사.
     */
    private boolean hasSelectStar(Select select) {
        if (!(select instanceof PlainSelect plainSelect)) {
            return false;
        }
        if (plainSelect.getSelectItems() != null) {
            for (var item : plainSelect.getSelectItems()) {
                // JSqlParser 4.9: SelectItem<T>가 AllColumns/AllTableColumns를 감싼다 — getExpression()으로 확인
                if (item.getExpression() instanceof AllColumns || item.getExpression() instanceof AllTableColumns) {
                    return true;
                }
            }
        }
        // 서브쿼리 재귀 검사
        if (plainSelect.getFromItem() instanceof Select subSelect) {
            if (hasSelectStar(subSelect)) return true;
        }
        return false;
    }

    /**
     * SQL 에서 참조된 테이블명 집합을 추출.
     */
    private Set<String> extractTables(Select select) {
        TablesNamesFinder finder = new TablesNamesFinder();
        List<String> tableList = finder.getTableList((Statement) select);
        Set<String> result = new HashSet<>();
        for (String t : tableList) {
            result.add(t.toLowerCase());
        }
        return result;
    }

    /**
     * SQL 에서 참조된 컬럼명 집합을 추출.
     * PlainSelect 의 selectItems 와 WHERE 절을 방문.
     */
    private Set<String> extractColumns(Select select) {
        Set<String> columns = new HashSet<>();
        if (!(select instanceof PlainSelect plainSelect)) return columns;

        // SELECT 절 컬럼
        if (plainSelect.getSelectItems() != null) {
            for (var item : plainSelect.getSelectItems()) {
                collectColumnsFromExpression(item.toString(), columns);
            }
        }

        // WHERE 절 컬럼
        Expression where = plainSelect.getWhere();
        if (where != null) {
            collectColumnsFromExpression(where.toString(), columns);
        }

        return columns;
    }

    /**
     * 표현식 문자열에서 Column AST 노드를 추출해 컬럼명 수집.
     * 간단한 방법: 표현식을 다시 파싱 후 Column visitor 적용.
     */
    private void collectColumnsFromExpression(String exprStr, Set<String> columns) {
        try {
            Expression expr = CCJSqlParserUtil.parseExpression(exprStr);
            expr.accept(new net.sf.jsqlparser.util.deparser.ExpressionDeParser() {
                @Override
                public void visit(Column column) {
                    columns.add(column.getColumnName());
                    super.visit(column);
                }
            });
        } catch (Exception e) {
            // 파싱 실패 시 스킵 (검증은 이미 CCJSqlParserUtil.parse 에서 통과함)
            log.trace("Column extraction skipped for: {}", exprStr);
        }
    }
}
