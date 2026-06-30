package com.ragvault.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragvault.core.repository.RagTableConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM 기반 RAG 테이블 컬럼·청킹 자동 추천.
 *
 * 선택된 테이블 전체를 하나의 프롬프트에 담아 LLM 1회 호출.
 * 응답 파싱 실패 시 빈 맵 반환 → 호출부에서 휴리스틱 fallback.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagColumnSuggestionService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RagTableConfigRepository ragTableConfigRepository;

    private static final Set<String> VALID_STRATEGIES = Set.of("recursive", "sentence", "fixed");

    private static final String SYSTEM_PROMPT =
            "당신은 RAG(Retrieval-Augmented Generation) 시스템 전문가입니다. " +
            "주어진 MySQL 테이블 스키마를 분석하여 벡터 임베딩에 최적화된 컬럼 설정과 청킹 전략을 JSON으로만 반환합니다. " +
            "설명이나 마크다운 없이 JSON 배열만 출력하세요.";

    private static final String USER_PROMPT_TEMPLATE =
            """
            아래 MySQL 테이블들의 RAG 임베딩 설정을 추천해주세요.

            선택 기준:
            - contentColumns: 자연어 텍스트가 담긴 컬럼 (설명, 본문, 댓글 등). 코드값/ID/날짜/숫자는 제외.
              ※ 각 테이블에 "[자동감지 텍스트 컬럼]" 목록이 표시됩니다. 이 컬럼들은 VARCHAR/TEXT 등 문자열 타입으로 확인된 후보입니다.
                반드시 이 목록을 참고하여 contentColumns와 titleColumn을 결정하세요. 목록에 없는 컬럼을 content로 지정하지 마세요.
            - titleColumn: 문서 제목 역할을 하는 컬럼 (없으면 null). 자동감지 목록 중에서 선택하세요.
            - metadataColumns: 필터링·출처 표시에 유용한 컬럼 (카테고리, 날짜, 작성자 등). PK·content·title 제외.
            - chunkingStrategy: "sentence"(문장 단위, 게시글·리뷰 등 자연어 글), "recursive"(범용), "fixed"(짧고 구조적인 텍스트)
            - chunkSize: 텍스트 길이에 맞게 추천 (200~2000). 짧은 상품명이면 작게, 긴 본문이면 크게.
            - chunkOverlap: chunkSize의 10~20% 권장.

            테이블 목록:
            {TABLE_LIST}

            아래 JSON 배열 형식으로만 응답하세요. 다른 텍스트 없이:
            [{"tableName":"...","contentColumns":["col1","col2"],"titleColumn":"col or null","metadataColumns":["col3"],"chunkingStrategy":"sentence|recursive|fixed","chunkSize":500,"chunkOverlap":50,"reason":"한 줄 이유"}]
            """;

    public record ColumnSuggestion(
            List<String> contentColumns,
            String titleColumn,
            List<String> metadataColumns,
            String chunkingStrategy,
            int chunkSize,
            int chunkOverlap,
            String reason
    ) {}

    /**
     * 테이블 목록을 LLM에 한 번에 전달해 컬럼·청킹 추천 반환.
     *
     * @return tableName → ColumnSuggestion 맵 (실패 시 빈 맵 → 호출부 휴리스틱 fallback)
     */
    public Map<String, ColumnSuggestion> suggestAll(List<SchemaInspectorService.TableInfo> tables) {
        if (tables.isEmpty()) return Map.of();

        String tableList = buildTableList(tables);
        String userPrompt = USER_PROMPT_TEMPLATE.replace("{TABLE_LIST}", tableList);

        String response;
        try {
            response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call().content();
            log.debug("RagColumn LLM response: {}", response);
        } catch (Exception e) {
            log.error("RagColumn LLM 호출 실패 — heuristic fallback: {}", e.getMessage());
            return Map.of();
        }

        return parseResponse(response);
    }

    /**
     * 백그라운드에서 LLM 분석 후 RagTableConfig 업데이트.
     * bulk import 직후 비동기 호출 — 완료 시 llmStatus = "done".
     */
    @Async
    public void suggestAndUpdateAsync(Integer dsId, List<String> tableNames,
                                      List<SchemaInspectorService.TableInfo> selectedSchemas) {
        log.info("Async LLM column suggestion start: dsId={}, tables={}", dsId, tableNames);
        Map<String, ColumnSuggestion> suggestions = suggestAll(selectedSchemas);

        for (String tableName : tableNames) {
            try {
                ragTableConfigRepository.findBySourceTableAndDatasourceId(tableName, dsId)
                        .ifPresent(config -> {
                            ColumnSuggestion s = suggestions.get(config.getSourceTable());
                            if (s != null) {
                                config.setTitleColumn(s.titleColumn());
                                config.setContentColumnsJson(String.join(",", s.contentColumns()));
                                config.setMetadataColumnsJson(String.join(",", s.metadataColumns()));
                                config.setChunkingStrategy(s.chunkingStrategy());
                                config.setChunkSize(s.chunkSize());
                                config.setChunkOverlap(s.chunkOverlap());
                                log.info("LLM suggestion applied: table={}, strategy={}, contentColumns={}",
                                        config.getSourceTable(), s.chunkingStrategy(), s.contentColumns());
                            } else {
                                log.info("LLM suggestion absent for table={} — autoDetect result retained",
                                        config.getSourceTable());
                            }
                            config.setLlmStatus("done");
                            ragTableConfigRepository.save(config);
                        });
            } catch (Exception e) {
                log.warn("Async LLM update failed: table={}, dsId={}, reason={}", tableName, dsId, e.getMessage());
                ragTableConfigRepository.findBySourceTableAndDatasourceId(tableName, dsId)
                        .ifPresent(config -> {
                            config.setLlmStatus("done");
                            ragTableConfigRepository.save(config);
                        });
            }
        }
        log.info("Async LLM column suggestion done: dsId={}, tables={}", dsId, tableNames);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private static final Set<String> TEXT_COLUMN_TYPES =
            Set.of("varchar", "text", "mediumtext", "longtext", "char", "tinytext");

    private String buildTableList(List<SchemaInspectorService.TableInfo> tables) {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (SchemaInspectorService.TableInfo t : tables) {
            String cols = t.columns().stream()
                    .map(c -> c.name() + "(" + c.dataType()
                            + (c.primaryKey() ? ",PK" : "")
                            + (c.comment().isBlank() ? "" : "," + c.comment()) + ")")
                    .collect(Collectors.joining(", "));
            String comment = t.tableComment().isBlank() ? "" : " /* " + t.tableComment() + " */";
            // 자동 감지된 텍스트 컬럼 목록 (LLM 힌트)
            String autoDetected = t.columns().stream()
                    .filter(c -> !c.primaryKey())
                    .filter(c -> TEXT_COLUMN_TYPES.contains(c.dataType().toLowerCase()))
                    .map(SchemaInspectorService.ColumnDetail::name)
                    .collect(Collectors.joining(", "));
            sb.append(idx++).append(". ").append(t.tableName()).append(comment)
              .append(": ").append(cols)
              .append("\n   [자동감지 텍스트 컬럼]: ").append(autoDetected.isEmpty() ? "(없음)" : autoDetected)
              .append("\n");
        }
        return sb.toString();
    }

    private Map<String, ColumnSuggestion> parseResponse(String response) {
        // 중첩 배열이 있을 때 비탐욕 정규식이 내부 배열을 먼저 잡는 문제 → indexOf/lastIndexOf로 대체
        String json = response;
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start >= 0 && end > start) {
            json = response.substring(start, end + 1);
        }

        try {
            List<Map<String, Object>> items = objectMapper.readValue(
                    json, new TypeReference<>() {});
            Map<String, ColumnSuggestion> result = new LinkedHashMap<>();
            for (Map<String, Object> item : items) {
                String tableName = (String) item.get("tableName");
                if (tableName == null) continue;

                List<String> contentCols = toStringList(item.get("contentColumns"));
                Object titleRaw = item.get("titleColumn");
                String titleCol = (titleRaw == null || "null".equals(titleRaw.toString())) ? null : titleRaw.toString();
                List<String> metaCols = toStringList(item.get("metadataColumns"));
                String strategy = validateStrategy((String) item.get("chunkingStrategy"));
                int chunkSize = toPositiveInt(item.get("chunkSize"), 500);
                int chunkOverlap = toNonNegInt(item.get("chunkOverlap"), 50);
                String reason = item.getOrDefault("reason", "").toString();

                result.put(tableName, new ColumnSuggestion(
                        contentCols, titleCol, metaCols, strategy, chunkSize, chunkOverlap, reason));
            }
            return result;
        } catch (Exception e) {
            log.warn("RagColumn 응답 파싱 실패 (heuristic fallback): {}", e.getMessage());
            return Map.of();
        }
    }

    private List<String> toStringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    private String validateStrategy(String raw) {
        if (raw != null && VALID_STRATEGIES.contains(raw.toLowerCase().trim())) {
            return raw.toLowerCase().trim();
        }
        return "recursive";
    }

    private int toPositiveInt(Object raw, int fallback) {
        try { int v = Integer.parseInt(raw.toString()); return v > 0 ? v : fallback; }
        catch (Exception e) { return fallback; }
    }

    private int toNonNegInt(Object raw, int fallback) {
        try { int v = Integer.parseInt(raw.toString()); return v >= 0 ? v : fallback; }
        catch (Exception e) { return fallback; }
    }
}
