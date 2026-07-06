package com.ragvault.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragvault.core.domain.SqlColumnDescription;
import com.ragvault.core.prompt.PromptLoader;
import com.ragvault.core.repository.SqlColumnDescriptionRepository;
import com.ragvault.core.repository.SqlTableConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 테이블·컬럼 자연어 설명 자동 생성 (SensitivityAnalysisService 패턴).
 *
 * COMMENT 우선: DB 의 TABLE_COMMENT/COLUMN_COMMENT 가 있으면 그대로 사용(source='comment').
 * 비어있는 항목만 모아 LLM 1회 호출로 설명 보충(source='llm').
 * 프롬프트에는 스키마(테이블·컬럼명·타입)만 전달 — 행 데이터 미포함(PII 안전).
 *
 * 결과: 테이블 설명 → sql_table_config.description, 컬럼 설명 → sql_column_description.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaDescriptionService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final SqlTableConfigRepository sqlTableConfigRepository;
    private final SqlColumnDescriptionRepository columnDescriptionRepository;
    private final RoutingEmbeddingService routingEmbeddingService;

    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[.*]", Pattern.DOTALL);

    private static final String SYSTEM_PROMPT =
            PromptLoader.load("prompts/schema-description/system.txt");

    private static final String USER_PROMPT_TEMPLATE =
            PromptLoader.load("prompts/schema-description/user-template.txt");

    /**
     * 데이터소스의 테이블·컬럼 설명을 자동 생성·저장 (백그라운드).
     * bulk import 직후 비동기 호출.
     */
    @Async
    public void generateAndStoreAsync(Integer dsId, List<SchemaInspectorService.TableInfo> tables) {
        if (tables == null || tables.isEmpty()) return;
        log.info("Schema description generation start: dsId={}, tables={}", dsId, tables.size());

        // 1. COMMENT 우선 저장 + LLM 대상(빈 COMMENT) 수집
        List<SchemaInspectorService.TableInfo> llmTargets = new ArrayList<>();
        for (SchemaInspectorService.TableInfo t : tables) {
            boolean tableNeedsLlm = t.tableComment() == null || t.tableComment().isBlank();
            if (!tableNeedsLlm) {
                storeTableDescription(dsId, t.tableName(), t.tableComment());
            }
            List<SchemaInspectorService.ColumnDetail> blankCols = new ArrayList<>();
            for (SchemaInspectorService.ColumnDetail c : t.columns()) {
                if (c.comment() != null && !c.comment().isBlank()) {
                    storeColumnDescription(dsId, t.tableName(), c.name(), c.comment(), "comment");
                } else {
                    blankCols.add(c);
                }
            }
            if (tableNeedsLlm || !blankCols.isEmpty()) {
                // LLM 에는 설명이 필요한 테이블/컬럼만 전달
                llmTargets.add(new SchemaInspectorService.TableInfo(
                        t.tableName(), t.tableComment(), blankCols));
            }
        }

        if (llmTargets.isEmpty()) {
            log.info("Schema description: COMMENT covers all, no LLM needed. dsId={}", dsId);
            return;
        }

        // 2. LLM 보충
        try {
            String userPrompt = USER_PROMPT_TEMPLATE.replace("{TABLE_LIST}", buildTableList(llmTargets));
            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call().content();
            applyLlmResult(dsId, response);
        } catch (Exception e) {
            log.warn("Schema description LLM failed (COMMENT-only fallback): dsId={}, error={}", dsId, e.getMessage());
        }

        // 설명이 채워졌으니 라우팅 임베딩 재색인 (Phase C)
        routingEmbeddingService.reindexDatasource(dsId);
        log.info("Schema description generation done: dsId={}", dsId);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private String buildTableList(List<SchemaInspectorService.TableInfo> tables) {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (SchemaInspectorService.TableInfo t : tables) {
            String cols = t.columns().stream()
                    .map(c -> c.name() + "(" + c.dataType() + (c.primaryKey() ? ",PK" : "") + ")")
                    .collect(Collectors.joining(", "));
            sb.append(idx++).append(". ").append(t.tableName())
              .append(": ").append(cols.isBlank() ? "(설명 필요 컬럼 없음)" : cols).append("\n");
        }
        return sb.toString();
    }

    private void applyLlmResult(Integer dsId, String response) {
        String json = response;
        Matcher m = JSON_ARRAY_PATTERN.matcher(response);
        if (m.find()) json = m.group();

        List<Map<String, Object>> items;
        try {
            items = objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Schema description JSON parse failed: {}", e.getMessage());
            return;
        }

        for (Map<String, Object> item : items) {
            String table = asString(item.get("table"));
            if (table == null) continue;
            String tableDesc = asString(item.get("description"));
            if (tableDesc != null && !tableDesc.isBlank()) {
                storeTableDescriptionIfBlank(dsId, table, tableDesc);
            }
            Object colsRaw = item.get("columns");
            if (colsRaw instanceof List<?> cols) {
                for (Object co : cols) {
                    if (co instanceof Map<?, ?> cm) {
                        String name = asString(cm.get("name"));
                        String desc = asString(cm.get("description"));
                        if (name != null && desc != null && !desc.isBlank()) {
                            storeColumnDescription(dsId, table, name, desc, "llm");
                        }
                    }
                }
            }
        }
    }

    private String asString(Object o) {
        return o == null ? null : o.toString();
    }

    /** 테이블 설명 저장 (COMMENT 출처 — 빈 경우에만). */
    private void storeTableDescription(Integer dsId, String table, String desc) {
        storeTableDescriptionIfBlank(dsId, table, desc);
    }

    private void storeTableDescriptionIfBlank(Integer dsId, String table, String desc) {
        sqlTableConfigRepository.findBySourceTableAndDatasourceId(table, dsId).ifPresent(config -> {
            if (config.getDescription() == null || config.getDescription().isBlank()) {
                config.setDescription(desc);
                sqlTableConfigRepository.save(config);
            }
        });
    }

    /** 컬럼 설명 upsert. */
    private void storeColumnDescription(Integer dsId, String table, String column, String desc, String source) {
        SqlColumnDescription entity = columnDescriptionRepository
                .findByDatasourceIdAndSourceTableAndColumnName(dsId, table, column)
                .orElseGet(SqlColumnDescription::new);
        entity.setDatasourceId(dsId);
        entity.setSourceTable(table);
        entity.setColumnName(column);
        entity.setDescription(desc);
        entity.setSource(source);
        entity.setUpdatedAt(Instant.now());
        columnDescriptionRepository.save(entity);
    }
}
