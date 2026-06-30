package com.ragvault.core.service;

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
            "당신은 MySQL SQL 생성 전문가입니다. 주어진 스키마와 질문을 보고 정확한 MySQL SELECT SQL만 출력하세요. " +
            "SQL 외 어떤 설명도 출력하지 마세요. 코드블록(```)도 사용하지 마세요. " +
            "질문에서 서로 다른 엔티티(테이블)의 목록을 각각 나열하도록 요청하는 경우(예: '게시글 목록과 댓글 목록을 알려줘', '사용자 목록과 주문 목록'), " +
            "각 엔티티를 별도의 SELECT로 작성하고 '---NEXT---' 구분자로 분리하여 출력하세요. " +
            "JOIN으로 표현 가능하더라도 각 엔티티를 독립적으로 나열하는 요청이라면 반드시 분리하세요. " +
            "단, '게시글별 댓글 수'처럼 두 테이블을 결합한 하나의 결과(집계, 관계 조회)를 요청하는 경우에는 JOIN을 사용하여 단일 SQL을 유지하세요. " +
            "절대 UNION, UNION ALL, INTERSECT, EXCEPT를 사용하지 마세요. " +
            "여러 결과가 필요하면 반드시 '---NEXT---' 구분자로만 분리하세요. UNION은 어떤 경우에도 금지입니다. " +
            "반드시 다음 규칙을 지키세요: " +
            "1. WHERE 조건이 없는 전체 목록 조회(예: '전체 게시글 목록', '모든 댓글')에는 반드시 LIMIT 1000을 추가하세요. " +
            "   JOIN도 없고 GROUP BY도 없고 WHERE도 없으면 반드시 LIMIT 1000을 사용하세요. " +
            "2. 컬럼 alias(AS)는 반드시 영문으로만 작성하세요. 한국어 alias는 절대 사용하지 마세요. " +
            "예: AS 이름 (X) → AS name (O), AS 제출일시 (X) → AS submitted_at (O). " +
            "3. 스키마에 없는 컬럼은 절대 사용하지 마세요. " +
            "4. SELECT * 는 사용하지 마세요. 필요한 컬럼만 명시하세요. " +
            "5. JOIN 조건에서 컬럼은 반드시 'alias.컬럼명' 형식으로 작성하세요. " +
            "테이블 alias와 컬럼명을 절대 붙여 쓰지 마세요. " +
            "예: ON l.id = ba.learner_id (O), ON l.id = balearner_id (X). " +
            "6. NULL이 될 수 있는 수치 컬럼에는 반드시 COALESCE를 사용하세요. " +
            "예: SUM(amount) (X) → SUM(COALESCE(amount, 0)) (O). " +
            "7. [비즈니스 규칙]에 '공유', '모두 공유', '독립적'이라고 명시된 엔티티(테이블)는: " +
            "사용자 질문에 특정 기수(N기)·날짜·이름 등이 포함되어 있어도 해당 테이블에 그 값으로 WHERE 조건을 추가하지 마세요. " +
            "공유 엔티티는 필터 없이 전체를 조회하세요. ";

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
