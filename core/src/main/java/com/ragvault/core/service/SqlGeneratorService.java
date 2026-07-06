package com.ragvault.core.service;

import com.ragvault.core.prompt.PromptLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ChatClient Few-shot 프롬프트로 MySQL SELECT SQL 을 생성하는 서비스.
 *
 * - 단일 또는 다중 SQL 반환 (---NEXT--- 구분자)
 * - 코드블록 제거 후 각 SELECT 문 추출
 * - 실패 시 빈 리스트 반환 (TextToSqlService 에서 에러 처리)
 *
 * ADR-0004: Spring AI ChatClient 전면 사용
 * requirements/08-text-to-sql.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlGeneratorService {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT =
            PromptLoader.load("prompts/sql-generator/system.txt");

    /**
     * 질문에 대한 MySQL SELECT SQL 목록을 생성해 반환.
     * 단일 질문에 대해 복수의 독립 쿼리가 필요한 경우 여러 항목을 반환한다.
     *
     * @param question       사용자 질문
     * @param schema         SchemaInspectorService 의 스키마 정보
     * @param tableDescriptions 테이블별 설명 (null 허용)
     * @param foreignKeys    SchemaInspectorService 의 FK 관계 목록 (null 허용)
     * @param sampleQueries  sql_table_config.sample_queries 합산 문자열 (null 허용)
     * @param businessRules  sql_table_config.business_rules 합산 문자열 (null 허용)
     * @param previousError  이전 검증 실패 사유 (null 허용) — P1 자가 수정 피드백
     * @return 생성된 SQL 목록. 실패 시 빈 리스트.
     */
    public List<String> generate(String question,
                                  Map<String, List<SchemaInspectorService.ColumnInfo>> schema,
                                  Map<String, String> tableDescriptions,
                                  List<SchemaInspectorService.FkInfo> foreignKeys,
                                  String sampleQueries,
                                  String businessRules,
                                  String previousError) {
        String prompt = buildPrompt(question, schema, tableDescriptions, foreignKeys,
                sampleQueries, businessRules, previousError);
        return callLlm(prompt);
    }

    private List<String> callLlm(String prompt) {
        try {
            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
                    .call()
                    .content();
            return extractQueries(response);
        } catch (Exception e) {
            log.error("LLM call failed during SQL generation: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 응답에서 코드블록 제거 후 ---NEXT--- 구분자로 분리하여 각 SELECT 문을 추출한다.
     */
    public List<String> extractQueries(String response) {
        if (response == null) return List.of();
        // 코드블록 마커만 제거하고 개행은 보존한다.
        String cleaned = response
                .replaceAll("(?i)```sql", "")
                .replaceAll("```", "")
                .trim();
        return Arrays.stream(cleaned.split("---NEXT---"))
                .map(String::trim)
                .map(part -> {
                    // SELECT 시작 지점부터 추출 (앞쪽 잡설 제거)
                    int idx = part.toUpperCase().indexOf("SELECT");
                    String s = idx >= 0 ? part.substring(idx) : part;
                    // 모델이 'SQL만 출력' 지시를 어기고 SQL 뒤에 설명을 덧붙이는 경우 방어.
                    int cut = s.length();
                    cut = earliest(cut, s.indexOf(';'));
                    cut = earliest(cut, indexOfBlankLine(s));
                    cut = earliest(cut, s.indexOf("\n#"));
                    cut = earliest(cut, s.indexOf("\n**"));
                    return s.substring(0, cut).trim();
                })
                .filter(s -> !s.isBlank() && s.toUpperCase().contains("SELECT"))
                .collect(Collectors.toList());
    }

    /** 빈 줄(개행 + 공백뿐인 줄)이 SQL과 설명을 가르는 경계. */
    private static final Pattern BLANK_LINE = Pattern.compile("\\n[ \\t]*\\r?\\n");

    /** candidate 가 유효하고(>=0) 현재 cut 보다 이르면 그 위치로 당긴다. */
    private static int earliest(int cut, int candidate) {
        return (candidate >= 0 && candidate < cut) ? candidate : cut;
    }

    /** 첫 빈 줄의 시작 인덱스, 없으면 -1. */
    private static int indexOfBlankLine(String s) {
        Matcher m = BLANK_LINE.matcher(s);
        return m.find() ? m.start() : -1;
    }

    private String buildPrompt(String question,
                               Map<String, List<SchemaInspectorService.ColumnInfo>> schema,
                               Map<String, String> tableDescriptions,
                               List<SchemaInspectorService.FkInfo> foreignKeys,
                               String sampleQueries,
                               String businessRules,
                               String previousError) {
        StringBuilder sb = new StringBuilder();
        sb.append("[스키마]\n");
        schema.forEach((table, cols) -> {
            String tableDesc = tableDescriptions == null ? null : tableDescriptions.get(table);
            sb.append("테이블: ").append(table);
            if (tableDesc != null && !tableDesc.isBlank()) {
                sb.append(" — ").append(tableDesc);
            }
            sb.append("\n");
            cols.forEach(c -> {
                sb.append("  - ").append(c.name())
                        .append(" (").append(c.dataType()).append(")");
                if (c.comment() != null && !c.comment().isBlank()) {
                    sb.append(": ").append(c.comment());
                }
                sb.append("\n");
            });
        });

        if (foreignKeys != null && !foreignKeys.isEmpty()) {
            sb.append("\n[테이블 관계 (FK)]\n");
            foreignKeys.forEach(fk ->
                sb.append("  - ").append(fk.tableName()).append(".").append(fk.columnName())
                  .append(" → ").append(fk.referencedTable()).append(".").append(fk.referencedColumn())
                  .append("\n")
            );
        }

        if (businessRules != null && !businessRules.isBlank()) {
            sb.append("\n[비즈니스 규칙 — 반드시 준수하세요]\n").append(businessRules).append("\n");
        }

        if (sampleQueries != null && !sampleQueries.isBlank() && !sampleQueries.equals("[]")) {
            sb.append("\n[예시 쿼리]\n").append(sampleQueries).append("\n");
        }

        if (previousError != null && !previousError.isBlank()) {
            sb.append("\n[이전 시도 오류 — 반드시 수정하세요]\n").append(previousError).append("\n");
        }

        sb.append("\n[질문]\n").append(question).append("\n\nSQL:");
        return sb.toString();
    }
}
