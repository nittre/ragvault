package com.ragservice.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ChatClient Few-shot 프롬프트로 MySQL SELECT SQL 을 생성하는 서비스.
 *
 * - 스키마 정보 + 예시 쿼리를 컨텍스트로 제공
 * - 코드블록 제거 후 첫 번째 SELECT 문만 반환
 * - 실패 시 null 반환 (TextToSqlService 에서 에러 처리)
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
            "반드시 다음 규칙을 지키세요: " +
            "1. 컬럼 alias(AS)는 반드시 영문으로만 작성하세요. 한국어 alias는 절대 사용하지 마세요. " +
            "예: AS 이름 (X) → AS name (O), AS 제출일시 (X) → AS submitted_at (O). " +
            "2. 스키마에 없는 컬럼은 절대 사용하지 마세요. " +
            "3. SELECT * 는 사용하지 마세요. 필요한 컬럼만 명시하세요.";

    /**
     * 질문에 대한 MySQL SELECT SQL 을 생성해 반환.
     *
     * @param question      사용자 질문
     * @param schema        SchemaInspectorService 의 스키마 정보
     * @param sampleQueries sql_table_config.sample_queries 합산 문자열 (null 허용)
     * @return 생성된 SQL, 실패 시 null
     */
    public String generate(String question,
                           Map<String, List<SchemaInspectorService.ColumnInfo>> schema,
                           String sampleQueries) {
        String prompt = buildPrompt(question, schema, sampleQueries);
        return callLlm(prompt);
    }

    private String callLlm(String prompt) {
        try {
            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
                    .call()
                    .content();
            return cleanSql(response);
        } catch (Exception e) {
            log.error("LLM call failed during SQL generation: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 응답에서 코드블록 제거 후 첫 번째 SELECT 문만 추출.
     */
    private String cleanSql(String response) {
        if (response == null) return null;
        String cleaned = response
                .replaceAll("(?i)```sql\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
        int idx = cleaned.toUpperCase().indexOf("SELECT");
        return idx >= 0 ? cleaned.substring(idx) : cleaned;
    }

    private String buildPrompt(String question,
                               Map<String, List<SchemaInspectorService.ColumnInfo>> schema,
                               String sampleQueries) {
        StringBuilder sb = new StringBuilder();
        sb.append("[스키마]\n");
        schema.forEach((table, cols) -> {
            sb.append("테이블: ").append(table).append("\n");
            cols.forEach(c -> {
                sb.append("  - ").append(c.name())
                        .append(" (").append(c.dataType()).append(")");
                if (c.comment() != null && !c.comment().isBlank()) {
                    sb.append(": ").append(c.comment());
                }
                sb.append("\n");
            });
        });

        if (sampleQueries != null && !sampleQueries.isBlank() && !sampleQueries.equals("[]")) {
            sb.append("\n[예시 쿼리]\n").append(sampleQueries).append("\n");
        }

        sb.append("\n[질문]\n").append(question).append("\n\nSQL:");
        return sb.toString();
    }
}
