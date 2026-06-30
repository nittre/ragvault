package com.ragvault.core.service;

import com.ragvault.core.service.SchemaInspectorService;
import com.ragvault.core.service.DataSourceConfigService;
import com.ragvault.core.service.RagTableConfigService;
import com.ragvault.core.service.SensitivityAnalysisService;
import com.ragvault.core.service.RagColumnSuggestionService;
import com.ragvault.core.service.SchemaDescriptionService;
import com.ragvault.core.service.SqlGeneratorService;
import com.ragvault.core.service.DataSourceRouterService;
import com.ragvault.core.service.RoutingEmbeddingService;
import com.ragvault.core.service.QueryIntent;



import com.ragvault.core.domain.SqlTableConfig;
import com.ragvault.core.repository.SqlTableConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        public static ValidationResult allow() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult deny(String reason) {
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

        // P4: 한글 식별자 환각 탐지 — 문자열 리터럴·alias 를 제거한 뒤 한글 여부 확인.
        // alias 는 결과 표시용이라 환각 위험 없음 → 허용.
        // AS <alias> 패턴 전체 제거 (alias 가 한글이어도 허용)
        String sqlNoLiterals = sql.replaceAll("'(?:[^'\\\\]|\\\\.)*'", "")
                                  .replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "")
                                  .replaceAll("(?i)\\bAS\\s+[\\w가-힣]+", "");
        Matcher korMatcher = Pattern.compile("[가-힣]+").matcher(sqlNoLiterals);
        if (korMatcher.find()) {
            return ValidationResult.deny(
                    "SQL에 한글 식별자가 포함되어 있습니다: '" + korMatcher.group() +
                    "'. 컬럼명과 alias는 영문만 사용하세요");
        }

        // 1. SELECT 전용 검사
        if (!(statement instanceof Select select)) {
            return ValidationResult.deny("SELECT 구문만 허용됩니다");
        }

        // 1-1. UNION/UNION ALL/INTERSECT/EXCEPT 차단
        // SetOperationList 는 Select 인터페이스를 구현하므로 별도로 체크해야 한다.
        if (select instanceof SetOperationList) {
            return ValidationResult.deny(
                    "UNION/UNION ALL은 허용되지 않습니다. " +
                    "여러 엔티티를 각각 조회하려면 ---NEXT--- 구분자로 분리된 독립 SELECT를 사용하세요");
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
     * SELECT·WHERE·JOIN ON·GROUP BY·HAVING·ORDER BY 모든 절을 방문한다.
     * (절 일부만 검사하면 GROUP BY/ORDER BY 등에 숨은 환각·PII 컬럼이 우회된다.)
     *
     * SELECT 항목은 alias 를 제외한 getExpression() 만 파싱한다 — "col AS x" 전체를
     * parseExpression 에 넘기면 실패해 컬럼이 누락되기 때문.
     */
    private Set<String> extractColumns(Select select) {
        Set<String> columns = new HashSet<>();
        if (!(select instanceof PlainSelect plainSelect)) return columns;

        // SELECT 절 (alias 제외한 표현식만)
        if (plainSelect.getSelectItems() != null) {
            for (var item : plainSelect.getSelectItems()) {
                if (item.getExpression() != null) {
                    collectColumnsFromExpression(item.getExpression().toString(), columns);
                }
            }
        }

        // WHERE 절
        Expression where = plainSelect.getWhere();
        if (where != null) {
            collectColumnsFromExpression(where.toString(), columns);
        }

        // JOIN ON 절
        if (plainSelect.getJoins() != null) {
            for (var join : plainSelect.getJoins()) {
                if (join.getOnExpressions() != null) {
                    for (Expression on : join.getOnExpressions()) {
                        collectColumnsFromExpression(on.toString(), columns);
                    }
                }
            }
        }

        // GROUP BY 절
        if (plainSelect.getGroupBy() != null
                && plainSelect.getGroupBy().getGroupByExpressionList() != null) {
            for (Object expr : plainSelect.getGroupBy().getGroupByExpressionList()) {
                collectColumnsFromExpression(expr.toString(), columns);
            }
        }

        // HAVING 절
        if (plainSelect.getHaving() != null) {
            collectColumnsFromExpression(plainSelect.getHaving().toString(), columns);
        }

        // ORDER BY 절
        if (plainSelect.getOrderByElements() != null) {
            for (var ob : plainSelect.getOrderByElements()) {
                if (ob.getExpression() != null) {
                    collectColumnsFromExpression(ob.getExpression().toString(), columns);
                }
            }
        }

        return columns;
    }

    /**
     * SELECT 절에 정의된 컬럼 alias 집합 (소문자).
     * ORDER BY/HAVING 등이 실제 컬럼이 아닌 alias 를 참조할 때 존재성 검사 오탐을 막기 위함.
     */
    private Set<String> extractAliases(Select select) {
        Set<String> aliases = new HashSet<>();
        if (!(select instanceof PlainSelect plainSelect)) return aliases;
        if (plainSelect.getSelectItems() != null) {
            for (var item : plainSelect.getSelectItems()) {
                if (item.getAlias() != null && item.getAlias().getName() != null) {
                    aliases.add(item.getAlias().getName().toLowerCase());
                }
            }
        }
        return aliases;
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

    /**
     * P2: 스키마 기반 컬럼 존재성 검증 오버로드.
     * 기본 validate(sql) 를 먼저 실행하고, 통과 시 스키마에 없는 컬럼(LLM 환각)을 추가 탐지.
     * 유사 컬럼명을 hint로 포함해 P1 자가 수정 루프에서 LLM이 정확한 컬럼명으로 재생성하도록 유도.
     *
     * @param sql    검증할 SQL
     * @param schema TextToSqlService 에서 이미 조회한 스키마 (null 허용 — null 이면 기본 검증만)
     */
    public ValidationResult validate(String sql,
                                     Map<String, List<SchemaInspectorService.ColumnInfo>> schema) {
        ValidationResult base = validate(sql);
        if (!base.allowed() || schema == null || schema.isEmpty()) return base;

        // 스키마 전체 컬럼명 집합 구축 (소문자)
        Set<String> knownColumns = new HashSet<>();
        for (List<SchemaInspectorService.ColumnInfo> cols : schema.values()) {
            for (SchemaInspectorService.ColumnInfo col : cols) {
                knownColumns.add(col.name().toLowerCase());
            }
        }

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            return base; // 기본 검증에서 이미 통과했으므로 재파싱 실패는 무시
        }

        Set<String> usedCols = extractColumns((Select) statement);
        Set<String> aliases = extractAliases((Select) statement);
        for (String col : usedCols) {
            // SELECT alias 는 실제 컬럼이 아니므로 존재성 검사에서 제외 (오탐 방지)
            if (aliases.contains(col.toLowerCase())) continue;
            if (!knownColumns.contains(col.toLowerCase())) {
                String similar = findSimilarColumn(col.toLowerCase(), knownColumns);
                String hint = similar != null ? " (혹시 '" + similar + "' 을 의도하셨나요?)" : "";
                return ValidationResult.deny("스키마에 존재하지 않는 컬럼: " + col + hint);
            }
        }

        // 항목 2: 정책 게이트 (풀스캔 방지 + NULL 산술 함정)
        if (((Select) statement) instanceof PlainSelect ps) {
            // 2-1. 풀스캔 방지 — WHERE·GROUP BY·집계·JOIN·LIMIT 모두 없는 단순 전체 행 나열은 거부
            // LIMIT이 명시된 쿼리는 행 수가 제한되므로 풀스캔이 아닌 것으로 처리한다
            boolean hasJoin = ps.getJoins() != null && !ps.getJoins().isEmpty();
            boolean hasLimit = ps.getLimit() != null;
            if (ps.getWhere() == null && ps.getGroupBy() == null && !hasAggregate(ps) && !hasJoin && !hasLimit) {
                return ValidationResult.deny(
                        "전체 테이블 스캔은 허용되지 않습니다. WHERE 조건, 집계(GROUP BY/SUM 등), 또는 LIMIT을 추가하세요");
            }
            // 2-2. NULL 산술 함정 — nullable 컬럼의 bare 집계는 COALESCE 강제
            ValidationResult nullCheck = checkNullArithmetic(ps, schema);
            if (nullCheck != null) return nullCheck;
            // 2-3. 0 나눗셈 함정 — 분모가 상수가 아니면 NULLIF(분모, 0) 강제
            ValidationResult divCheck = checkDivisionByZero(ps);
            if (divCheck != null) return divCheck;
        }

        return base;
    }

    private static final Pattern AGG_PATTERN =
            Pattern.compile("\\b(SUM|AVG|MIN|MAX|COUNT)\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** SELECT 절에 집계함수(SUM/AVG/MIN/MAX/COUNT)가 있으면 true. */
    private boolean hasAggregate(PlainSelect ps) {
        if (ps.getSelectItems() == null) return false;
        for (var item : ps.getSelectItems()) {
            if (item.getExpression() != null
                    && AGG_PATTERN.matcher(item.getExpression().toString()).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * NULL 산술 함정 검사: SUM/AVG 의 인자가 bare Column 이고 schema 상 nullable 이며
     * COALESCE/IFNULL 로 감싸이지 않았으면 거부(자동 수정안 포함).
     * 중첩 표현식은 오탐 회피를 위해 통과시킨다.
     *
     * @return 위반 시 deny ValidationResult, 정상이면 null
     */
    private ValidationResult checkNullArithmetic(
            PlainSelect ps, Map<String, List<SchemaInspectorService.ColumnInfo>> schema) {
        if (ps.getSelectItems() == null) return null;

        Set<String> nullableCols = new HashSet<>();
        for (List<SchemaInspectorService.ColumnInfo> cols : schema.values()) {
            for (SchemaInspectorService.ColumnInfo c : cols) {
                if (c.nullable()) nullableCols.add(c.name().toLowerCase());
            }
        }
        if (nullableCols.isEmpty()) return null;

        for (var item : ps.getSelectItems()) {
            Expression e = item.getExpression();
            if (!(e instanceof Function f)) continue;
            String fname = f.getName() == null ? "" : f.getName().toLowerCase();
            if (!fname.equals("sum") && !fname.equals("avg")) continue;

            var params = f.getParameters();
            if (params == null || params.size() != 1) continue;
            Object arg0 = params.get(0);
            if (!(arg0 instanceof Column col)) continue;

            String cn = col.getColumnName().toLowerCase();
            if (nullableCols.contains(cn)) {
                return ValidationResult.deny(
                        "NULL 가능 컬럼 '" + col.getColumnName() + "' 의 집계는 COALESCE 가 필요합니다. 예: "
                        + fname.toUpperCase() + "(COALESCE(" + col.getColumnName() + ", 0))");
            }
        }
        return null;
    }

    /**
     * 0 나눗셈 함정 검사: SELECT 절의 나눗셈(/) 분모가 0이 될 수 있으면 거부(자동 수정안 포함).
     * - 분모가 0이 아닌 상수 리터럴(LongValue/DoubleValue) 이면 통과.
     * - 분모가 NULLIF(...) 로 이미 감싸여 있으면 통과.
     * - 그 외(컬럼·함수·식)는 NULLIF(분모, 0) 강제.
     * 중첩 산술까지 잡기 위해 ExpressionDeParser 로 Division 노드를 재귀 방문한다.
     * checkNullArithmetic 의 수정안 반환 톤과 일치.
     *
     * @return 위반 시 deny ValidationResult, 정상이면 null
     */
    private ValidationResult checkDivisionByZero(PlainSelect ps) {
        if (ps.getSelectItems() == null) return null;

        // 방문 중 발견한 첫 위반 분모 식 (없으면 null)
        String[] violation = new String[]{null};

        var visitor = new net.sf.jsqlparser.util.deparser.ExpressionDeParser() {
            @Override
            public void visit(Division division) {
                if (violation[0] == null && !isSafeDenominator(division.getRightExpression())) {
                    violation[0] = division.getRightExpression().toString();
                }
                super.visit(division);
            }
        };

        for (var item : ps.getSelectItems()) {
            if (item.getExpression() == null) continue;
            item.getExpression().accept(visitor);
            if (violation[0] != null) {
                String denom = violation[0];
                return ValidationResult.deny(
                        "0 나눗셈 위험: 분모 '" + denom + "' 를 NULLIF(" + denom + ", 0) 로 감싸세요");
            }
        }
        return null;
    }

    /** 분모가 0이 될 수 없다고 단정할 수 있으면 true (0이 아닌 상수 또는 NULLIF 래핑). */
    private boolean isSafeDenominator(Expression denom) {
        if (denom instanceof LongValue lv) {
            return lv.getValue() != 0L;
        }
        if (denom instanceof DoubleValue dv) {
            return dv.getValue() != 0.0;
        }
        if (denom instanceof Function f) {
            String fname = f.getName() == null ? "" : f.getName().toLowerCase();
            return fname.equals("nullif");
        }
        return false;
    }

    /**
     * 유사 컬럼명 탐색 — Levenshtein 거리 ≤ 3 인 가장 가까운 후보를 반환.
     */
    private String findSimilarColumn(String target, Set<String> candidates) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String c : candidates) {
            int dist = levenshtein(target, c);
            if (dist < bestDist && dist <= 3) {
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }

    private int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? dp[i - 1][j - 1]
                        : 1 + Math.min(dp[i - 1][j - 1], Math.min(dp[i - 1][j], dp[i][j - 1]));
            }
        }
        return dp[a.length()][b.length()];
    }
}
