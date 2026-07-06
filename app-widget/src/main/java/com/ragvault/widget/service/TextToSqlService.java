package com.ragvault.widget.service;

import com.ragvault.core.service.SchemaInspectorService;
import com.ragvault.core.service.SqlExecutorService;
import com.ragvault.core.service.SqlValidator;
import com.ragvault.core.service.RagTableConfigService;
import com.ragvault.core.service.SensitivityAnalysisService;
import com.ragvault.core.service.RagColumnSuggestionService;
import com.ragvault.core.service.SchemaDescriptionService;
import com.ragvault.core.service.SqlGeneratorService;
import com.ragvault.core.service.DataSourceRouterService;
import com.ragvault.core.service.RoutingEmbeddingService;
import com.ragvault.core.service.QueryIntent;

import com.ragvault.core.domain.SqlExecutionLog;
import com.ragvault.core.domain.SqlTableConfig;
import com.ragvault.core.prompt.PromptLoader;
import com.ragvault.core.repository.SqlExecutionLogRepository;
import com.ragvault.core.repository.SqlTableConfigRepository;
import com.ragvault.core.security.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Text-to-SQL 전체 경로 서비스 (축소 이식).
 *
 * 처리 흐름:
 * 0. 데이터소스 라우팅 (DataSourceRouterService)
 * 1. 스키마 조회 (SchemaInspectorService)
 * 2. SQL 생성 (SqlGeneratorService — 멀티 쿼리, ---NEXT---)
 * 3. SQL 검증 (SqlValidator — Layer 1, 쿼리별 + P1 자가 수정)
 * 4. SQL 실행 (SqlExecutorService — read-only)
 * 5. 자연어화 LLM 호출 (ChatClient)
 * 6. PII 마스킹 (PiiMasker)
 * 7. 실행 로그 저장 (SqlExecutionLogRepository)
 *
 * ragvault 범위 밖인 CSV 다운로드/원본 저장/비즈니스 규칙은 제거.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextToSqlService {

    private final SchemaInspectorService schemaInspector;
    private final SqlGeneratorService sqlGenerator;
    private final SqlValidator sqlValidator;
    private final SqlExecutorService sqlExecutor;
    private final ChatClient chatClient;
    private final PiiMasker piiMasker;
    private final SqlTableConfigRepository sqlTableConfigRepository;
    private final SqlExecutionLogRepository sqlExecutionLogRepository;
    private final DataSourceRouterService dataSourceRouter;

    private static final int PREVIEW_ROWS = 50;
    private static final int TABLE_CELL_MAX_LEN = 80;

    private static final String SYNTHESIS_SYSTEM =
            PromptLoader.load("prompts/text-to-sql-synthesis/system.txt");

    private static final String DENIAL_SYSTEM =
            PromptLoader.load("prompts/text-to-sql-denial/system.txt");

    private record ResultSection(List<Map<String, Object>> previewRows, int totalCount) {}

    public record SqlQueryResult(String content, String generatedSql, String intent,
                                 String responseId, boolean denied, boolean hasRows) {
        static SqlQueryResult denied(String reason) {
            return new SqlQueryResult(reason, null, "SQL", null, true, false);
        }
    }

    /**
     * 자연어 질문을 SQL 로 변환 → 실행 → 자연어 응답 반환.
     */
    public SqlQueryResult query(String question, String userEmail) {
        long start = System.currentTimeMillis();

        Integer datasourceId = dataSourceRouter.route(question);
        if (datasourceId == null) {
            return SqlQueryResult.denied("등록된 데이터소스가 없습니다. 관리자에게 문의하세요.");
        }
        log.debug("Routed to datasourceId={}", datasourceId);

        Map<String, List<SchemaInspectorService.ColumnInfo>> schema =
                schemaInspector.getSchemaForActiveTables(datasourceId);
        if (schema.isEmpty()) {
            return SqlQueryResult.denied(
                    "SQL 조회 가능한 테이블이 등록되지 않았습니다. 관리자에게 문의하세요.");
        }

        List<SchemaInspectorService.FkInfo> foreignKeys =
                schemaInspector.getForeignKeysForActiveTables(datasourceId);

        String sampleQueries = collectSampleQueries();
        Map<String, String> tableDescriptions = collectTableDescriptions(datasourceId);

        // SQL 생성 + 검증 — P1 자가 수정 루프
        final int MAX_RETRIES = 2;
        String previousError = null;
        List<String> generatedSqls = null;
        List<SqlValidator.ValidationResult> validations = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            generatedSqls = sqlGenerator.generate(question, schema, tableDescriptions, foreignKeys,
                    sampleQueries, null, previousError);

            if (generatedSqls == null || generatedSqls.isEmpty()) {
                logExecution(userEmail, question, null, "denied", "SQL 생성 실패", "error",
                        null, null, null, "OTHER");
                return SqlQueryResult.denied(
                        "SQL을 생성하지 못했습니다. 질문을 다시 시도해주세요. (err_sql_gen)");
            }
            log.debug("Generated {} SQL(s) (attempt={}/{}): {}",
                    generatedSqls.size(), attempt + 1, MAX_RETRIES + 1, generatedSqls);

            validations = new ArrayList<>();
            StringBuilder errorSb = new StringBuilder();
            boolean allAllowed = true;

            for (String sql : generatedSqls) {
                SqlValidator.ValidationResult v = sqlValidator.validate(sql, schema);
                if (v.allowed()) {
                    SqlExecutorService.DryRunResult dryRun = sqlExecutor.explain(sql, datasourceId);
                    if (!dryRun.ok()) {
                        v = SqlValidator.ValidationResult.deny("[dry-run] " + dryRun.error());
                    }
                }
                validations.add(v);
                if (!v.allowed()) {
                    allAllowed = false;
                    errorSb.append("SQL: ").append(sql).append("\n오류: ").append(v.reason()).append("\n\n");
                }
            }

            if (allAllowed) break;

            String currentError = errorSb.toString().trim();
            if (currentError.equals(previousError)) {
                log.warn("SQL validation 같은 위반 반복 — 조기 중단 (attempt={})", attempt + 1);
                break;
            }
            previousError = currentError;
            log.warn("SQL validation failed (attempt={}/{}): {}", attempt + 1, MAX_RETRIES + 1, currentError);
        }

        for (int i = 0; i < validations.size(); i++) {
            SqlValidator.ValidationResult v = validations.get(i);
            if (!v.allowed()) {
                String failedSql = generatedSqls.get(i);
                log.warn("SQL validation denied: {} | reason: {}", failedSql, v.reason());
                logExecution(userEmail, question, failedSql, "denied", v.reason(),
                        null, null, null, null, categorize(v.reason(), null));
                String explanation = explainDenial(question, schema, previousError);
                return SqlQueryResult.denied(explanation);
            }
        }

        // SQL 실행
        List<SqlExecutorService.SqlResult> sqlResults = new ArrayList<>();
        for (String sql : generatedSqls) {
            SqlExecutorService.SqlResult result = sqlExecutor.execute(sql, datasourceId);
            if (result.hasError()) {
                long elapsedOnErr = System.currentTimeMillis() - start;
                log.error("SQL exec failed | sql={} | error={}", sql, result.error());
                logExecution(userEmail, question, sql, "allowed", null, "error",
                        null, (int) elapsedOnErr, result.error(), categorize(null, result.error()));
                return SqlQueryResult.denied("데이터 조회 중 오류가 발생했습니다. (err_sql_exec)");
            }
            sqlResults.add(result);
        }

        // 미리보기 준비
        List<ResultSection> sections = new ArrayList<>();
        for (SqlExecutorService.SqlResult result : sqlResults) {
            List<Map<String, Object>> rows = result.rows();
            List<Map<String, Object>> previewRows = rows.size() > PREVIEW_ROWS
                    ? rows.subList(0, PREVIEW_ROWS) : rows;
            sections.add(new ResultSection(previewRows, rows.size()));
        }

        // 자연어화 LLM 호출
        String synthesisPrompt = buildSynthesisPrompt(question, generatedSqls, sections);
        String rawResponse = chatClient.prompt()
                .system(SYNTHESIS_SYSTEM)
                .user(synthesisPrompt)
                .call()
                .content();

        boolean isMulti = sections.size() > 1;
        StringBuilder fullResponse = new StringBuilder(rawResponse);
        for (int i = 0; i < sections.size(); i++) {
            ResultSection section = sections.get(i);
            if (!section.previewRows().isEmpty()) {
                if (isMulti) {
                    fullResponse.append("\n\n**조회 결과 ").append(i + 1).append("**");
                }
                fullResponse.append("\n\n").append(rowsToMarkdownTable(section.previewRows()));
                if (section.totalCount() > section.previewRows().size()) {
                    fullResponse.append("\n\n> 전체 ").append(section.totalCount()).append("건 중 ")
                            .append(section.previewRows().size()).append("건만 표시됩니다.");
                }
            }
        }

        String masked = piiMasker.mask(fullResponse.toString());

        long elapsed = System.currentTimeMillis() - start;

        for (int i = 0; i < generatedSqls.size(); i++) {
            logExecution(userEmail, question, generatedSqls.get(i), "allowed", null, "success",
                    sqlResults.get(i).rowCount(), (int) elapsed, null, null);
        }

        String allSqls = String.join("\n---\n", generatedSqls);
        boolean hasRows = sqlResults.stream().anyMatch(r -> r.rowCount() > 0);
        return new SqlQueryResult(masked, allSqls, "SQL", null, false, hasRows);
    }

    private String collectSampleQueries() {
        return sqlTableConfigRepository.findByIsActiveTrue().stream()
                .filter(c -> c.getSampleQueries() != null && !c.getSampleQueries().isBlank())
                .map(SqlTableConfig::getSampleQueries)
                .collect(Collectors.joining(", "));
    }

    private Map<String, String> collectTableDescriptions(Integer datasourceId) {
        Map<String, String> map = new java.util.HashMap<>();
        for (SqlTableConfig c : sqlTableConfigRepository.findByDatasourceIdAndIsActiveTrue(datasourceId)) {
            if (c.getDescription() != null && !c.getDescription().isBlank()) {
                map.put(c.getSourceTable(), c.getDescription());
            }
        }
        return map;
    }

    private String buildSynthesisPrompt(String question, List<String> sqls, List<ResultSection> sections) {
        StringBuilder sb = new StringBuilder();
        sb.append("[질문]\n").append(question).append("\n\n");

        boolean isMulti = sqls.size() > 1;

        for (int i = 0; i < sqls.size(); i++) {
            ResultSection section = sections.get(i);
            if (isMulti) {
                sb.append("[쿼리 ").append(i + 1).append("]\n").append(sqls.get(i)).append("\n\n");
                sb.append("[조회 결과 ").append(i + 1).append("]\n");
            } else {
                sb.append("[실행된 SQL]\n").append(sqls.get(0)).append("\n\n");
                sb.append("[조회 결과]\n");
            }

            if (section.previewRows().isEmpty()) {
                sb.append("(결과 없음)\n");
                sb.append("\n조회된 데이터가 없습니다. 이를 한국어로 안내해주세요.");
            } else {
                sb.append(rowsToMarkdownTable(section.previewRows())).append("\n");
                sb.append("\n총 ").append(section.totalCount()).append("건입니다. ");
                sb.append("조회 결과를 한국어로 간결하게 요약해주세요. 데이터 테이블은 이미 화면에 표시됩니다.");
            }
            sb.append("\n\n");
        }

        if (isMulti) {
            sb.append("위 여러 조회 결과를 종합하여 한국어로 간결하게 요약해주세요.");
        }

        return sb.toString();
    }

    private String rowsToMarkdownTable(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "(결과 없음)";
        List<String> headers = List.copyOf(rows.get(0).keySet());
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        sb.append("|").append(" --- |".repeat(headers.size())).append("\n");
        for (Map<String, Object> row : rows) {
            sb.append("| ")
              .append(headers.stream()
                  .map(h -> sanitizeCell(String.valueOf(row.getOrDefault(h, ""))))
                  .collect(Collectors.joining(" | ")))
              .append(" |\n");
        }
        return sb.toString();
    }

    private String sanitizeCell(String value) {
        if (value == null || value.equals("null")) return "";
        String cleaned = value.replace("\r\n", " ").replace("\n", " ").replace("\r", " ");
        cleaned = cleaned.replace("|", "\\|");
        if (cleaned.length() > TABLE_CELL_MAX_LEN) {
            cleaned = cleaned.substring(0, TABLE_CELL_MAX_LEN) + "…";
        }
        return cleaned;
    }

    private void logExecution(String userEmail, String question, String sql,
                               String validationResult, String validationReason,
                               String executionStatus, Integer rowCount, Integer elapsedMs,
                               String errorMessage, String failureCategory) {
        try {
            SqlExecutionLog entry = SqlExecutionLog.builder()
                    .userEmail(userEmail)
                    .intent("SQL")
                    .question(question)
                    .generatedSql(sql)
                    .validationResult(validationResult)
                    .validationReason(validationReason)
                    .executionStatus(executionStatus)
                    .rowCount(rowCount)
                    .elapsedMs(elapsedMs)
                    .errorMessage(errorMessage)
                    .failureCategory(failureCategory)
                    .createdAt(LocalDateTime.now())
                    .build();
            sqlExecutionLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("SqlExecutionLog save failed (non-fatal): {}", e.getMessage());
        }
    }

    private String explainDenial(String question,
                                  Map<String, List<SchemaInspectorService.ColumnInfo>> schema,
                                  String lastError) {
        try {
            StringBuilder ctx = new StringBuilder();
            ctx.append("[스키마 테이블 목록]\n");
            schema.forEach((table, cols) -> {
                ctx.append("- ").append(table).append(": ");
                ctx.append(cols.stream().map(SchemaInspectorService.ColumnInfo::name)
                        .collect(Collectors.joining(", ")));
                ctx.append("\n");
            });
            if (lastError != null && !lastError.isBlank()) {
                ctx.append("\n[조회 실패 원인]\n").append(lastError).append("\n");
            }

            String prompt = ctx
                    + "\n[사용자 질문]\n" + question
                    + "\n\n위 스키마 제약을 근거로 왜 조회가 불가능한지 2-3문장으로 한국어로 설명하고, "
                    + "가능한 대안 질문을 1개 제시하세요. "
                    + "단, 응답에 테이블명·컬럼명·SQL 구문 등 기술적 세부사항을 절대 포함하지 마세요. "
                    + "비즈니스 도메인 언어로만 설명하고, 대안 제시는 '원하신다면 ~해드릴까요?' 형태로 자연스럽게 작성하세요.";

            String response = chatClient.prompt()
                    .system(DENIAL_SYSTEM)
                    .user(prompt)
                    .call()
                    .content();

            String masked = piiMasker.mask(response);
            return masked != null && !masked.isBlank()
                    ? masked
                    : "질문을 처리하기 어렵습니다. 조회 기간·대상 등 조건을 조금 더 구체적으로 알려주세요. (err_sql_val)";
        } catch (Exception e) {
            log.warn("explainDenial LLM call failed (non-fatal): {}", e.getMessage());
            return "질문을 처리하기 어렵습니다. 조회 기간·대상 등 조건을 조금 더 구체적으로 알려주세요. (err_sql_val)";
        }
    }

    private static String categorize(String reason, String errorMsg) {
        if (reason == null && errorMsg == null) return null;
        String r = reason != null ? reason : "";
        String e = errorMsg != null ? errorMsg.toLowerCase() : "";
        if (r.contains("존재하지 않는 컬럼") || r.contains("한글 식별자") || r.startsWith("[dry-run]"))
            return "HALLUCINATED_COLUMN";
        if (r.contains("COALESCE") || r.contains("NULL 가능") || r.contains("0 나눗셈") || r.contains("NULLIF"))
            return "NULL_ARITHMETIC";
        if (r.contains("전체 테이블 스캔"))
            return "FULL_SCAN";
        if (r.contains("SQL 구문 오류") || r.contains("SELECT *") || r.contains("SELECT 구문만"))
            return "SYNTAX_ERROR";
        if (e.contains("group by") || e.contains("aggregate") || e.contains("group"))
            return "AGGREGATE_ERROR";
        return "OTHER";
    }
}
