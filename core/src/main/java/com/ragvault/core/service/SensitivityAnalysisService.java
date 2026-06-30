package com.ragvault.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragvault.core.domain.SqlTableConfig;
import com.ragvault.core.repository.SqlTableConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LLM 기반 테이블 데이터 민감도 자동 분류.
 *
 * 선택된 테이블 전체를 하나의 프롬프트에 담아 LLM 1회 호출로 처리.
 * 응답 파싱 실패 시 "internal" 로 fallback.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitivityAnalysisService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final SqlTableConfigRepository sqlTableConfigRepository;

    private static final Pattern JSON_ARRAY_PATTERN =
            Pattern.compile("\\[.*?\\]", Pattern.DOTALL);

    private static final String SYSTEM_PROMPT =
            "당신은 데이터베이스 보안 전문가입니다. " +
            "주어진 테이블 스키마를 분석하여 데이터 민감도를 JSON으로만 반환합니다. " +
            "설명이나 마크다운 없이 JSON 배열만 출력하세요.";

    private static final String USER_PROMPT_TEMPLATE =
            """
            아래 MySQL 테이블들의 데이터 민감도를 분류해주세요.

            민감도 레벨:
            - public: 외부 공개 가능 데이터 (상품 카탈로그, 공지사항, 카테고리 등)
            - internal: 사내 업무 데이터 (주문, 재고, 로그, 설정 등)
            - confidential: 개인정보 포함 (이름, 이메일, 전화번호, 주소, 생년월일 등 PII)
            - restricted: 고민감 금융·의료 데이터 (주민번호, 카드번호, 계좌번호, 비밀번호 등)

            테이블 목록:
            {TABLE_LIST}

            아래 JSON 배열 형식으로만 응답하세요. 다른 텍스트 없이:
            [{"tableName":"...","sensitivity":"public|internal|confidential|restricted","reason":"한 줄 이유"}]
            """;

    public record SensitivityResult(String sensitivity, String reason) {}

    /**
     * 테이블 목록을 LLM에 한 번에 전달해 민감도 분류 반환.
     *
     * @param tables 분석할 테이블 목록
     * @return tableName → SensitivityResult 맵 (실패 시 빈 맵 → 호출부에서 "internal" fallback)
     */
    public Map<String, SensitivityResult> analyzeAll(List<SchemaInspectorService.TableInfo> tables) {
        if (tables.isEmpty()) return Map.of();

        String tableList = buildTableList(tables);
        String userPrompt = USER_PROMPT_TEMPLATE.replace("{TABLE_LIST}", tableList);

        String response;
        try {
            response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call().content();
            log.debug("Sensitivity LLM response: {}", response);
        } catch (Exception e) {
            log.error("Sensitivity LLM 호출 실패 — internal fallback: {}", e.getMessage());
            return Map.of();
        }

        return parseResponse(response);
    }

    /**
     * 백그라운드에서 LLM 민감도 분석 후 SqlTableConfig 업데이트.
     * bulk import 직후 비동기 호출 — 완료 시 llmStatus = "done".
     */
    @Async
    public void analyzeAndUpdateAsync(Integer dsId, List<String> tableNames,
                                      List<SchemaInspectorService.TableInfo> selectedSchemas) {
        log.info("Async sensitivity analysis start: dsId={}, tables={}", dsId, tableNames);
        Map<String, SensitivityResult> results = analyzeAll(selectedSchemas);

        for (String tableName : tableNames) {
            try {
                sqlTableConfigRepository.findBySourceTableAndDatasourceId(tableName, dsId)
                        .ifPresent(config -> {
                            SensitivityResult r = results.get(config.getSourceTable());
                            if (r != null && !"restricted".equals(r.sensitivity())) {
                                config.setDataSensitivity(r.sensitivity());
                                log.info("Sensitivity updated: table={}, sensitivity={}", config.getSourceTable(), r.sensitivity());
                            }
                            config.setLlmStatus("done");
                            sqlTableConfigRepository.save(config);
                        });
            } catch (Exception e) {
                log.warn("Async sensitivity update failed: table={}, reason={}", tableName, e.getMessage());
                sqlTableConfigRepository.findBySourceTableAndDatasourceId(tableName, dsId)
                        .ifPresent(config -> {
                            config.setLlmStatus("done");
                            sqlTableConfigRepository.save(config);
                        });
            }
        }
        log.info("Async sensitivity analysis done: dsId={}, tables={}", dsId, tableNames);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private String buildTableList(List<SchemaInspectorService.TableInfo> tables) {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (SchemaInspectorService.TableInfo t : tables) {
            String cols = t.columns().stream()
                    .map(c -> c.name() + "(" + c.dataType() + (c.primaryKey() ? ",PK" : "") +
                              (c.comment().isBlank() ? "" : "," + c.comment()) + ")")
                    .collect(Collectors.joining(", "));
            String comment = t.tableComment().isBlank() ? "" : " /* " + t.tableComment() + " */";
            sb.append(idx++).append(". ").append(t.tableName()).append(comment)
              .append(": ").append(cols).append("\n");
        }
        return sb.toString();
    }

    private Map<String, SensitivityResult> parseResponse(String response) {
        // JSON 배열 부분 추출 (LLM이 마크다운 코드블록을 붙이는 경우 대비)
        String json = response;
        Matcher matcher = JSON_ARRAY_PATTERN.matcher(response);
        if (matcher.find()) {
            json = matcher.group();
        }

        try {
            List<Map<String, String>> items = objectMapper.readValue(
                    json, new TypeReference<>() {});
            Map<String, SensitivityResult> result = new LinkedHashMap<>();
            for (Map<String, String> item : items) {
                String tableName = item.get("tableName");
                String sensitivity = validateSensitivity(item.get("sensitivity"));
                String reason = item.getOrDefault("reason", "");
                if (tableName != null) {
                    result.put(tableName, new SensitivityResult(sensitivity, reason));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Sensitivity 응답 파싱 실패 (internal fallback): {}", e.getMessage());
            return Map.of();
        }
    }

    private String validateSensitivity(String raw) {
        if (raw == null) return "internal";
        return switch (raw.toLowerCase().trim()) {
            case "public"       -> "public";
            case "confidential" -> "confidential";
            case "restricted"   -> "restricted";
            default             -> "internal";
        };
    }
}
