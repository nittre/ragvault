package com.ragvault.core.service;

import com.ragvault.core.domain.DataSourceConfig;
import com.ragvault.core.domain.SqlTableConfig;
import com.ragvault.core.prompt.PromptLoader;
import com.ragvault.core.repository.SqlTableConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 사용자 질문에 가장 적합한 datasource_id 를 결정하는 라우터 서비스.
 *
 * - 활성 datasource 0개 → null (등록된 datasource 없음)
 * - 활성 datasource 1개 → 즉시 반환 (LLM 불필요)
 * - 활성 datasource ≥2 → LLM 라우팅 (datasource 목록 + 활성 테이블 목록 전달)
 *
 * Spring AI ChatClient 사용 (ADR-0004).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceRouterService {

    private final DataSourceConfigService dataSourceConfigService;
    private final SqlTableConfigRepository sqlTableConfigRepository;
    private final ChatClient chatClient;
    private final RoutingEmbeddingService routingEmbeddingService;

    /** 활성 데이터소스가 이 수 이상이면 임베딩으로 후보를 먼저 좁힌다. */
    private static final int EMBEDDING_SHORTLIST_THRESHOLD = 4;
    private static final int EMBEDDING_SHORTLIST_TOPK = 3;

    private static final String SYSTEM_PROMPT =
            PromptLoader.load("prompts/data-source-router/system.txt");

    /**
     * 사용자 질문에 가장 적합한 datasource_id 반환.
     *
     * @param userQuery 사용자 자연어 질문
     * @return datasource_id, null = 활성 데이터소스 없음
     */
    public Integer route(String userQuery) {
        List<DataSourceConfig> actives = dataSourceConfigService.findActiveAll();

        if (actives.isEmpty()) {
            log.debug("No active datasources registered");
            return null;
        }
        if (actives.size() == 1) {
            log.debug("Single active datasource — routing to id={}", actives.get(0).getId());
            return actives.get(0).getId();
        }

        // 활성 datasource ≥2: LLM 라우팅
        // ID 오름차순 정렬로 fallback 결정론적 보장
        List<DataSourceConfig> sorted = actives.stream()
                .sorted(java.util.Comparator.comparingInt(DataSourceConfig::getId))
                .toList();

        // 데이터소스가 많으면 임베딩으로 후보를 먼저 좁혀 프롬프트를 작게 유지 (Phase C)
        List<DataSourceConfig> candidates = shortlistByEmbedding(userQuery, sorted);

        String prompt = buildRoutingPrompt(userQuery, candidates);
        try {
            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
                    .call()
                    .content();
            return parseRoutingResponse(response, candidates);
        } catch (Exception e) {
            log.warn("DataSource routing LLM call failed, falling back to lowest-id active: {}", e.getMessage());
            return candidates.get(0).getId();
        }
    }

    /**
     * 활성 데이터소스가 임계치 이상이면 질문 임베딩으로 후보를 좁힌다.
     * 임베딩 결과가 비거나 임계치 미만이면 전체 목록을 그대로 반환(결정론적 fallback).
     */
    private List<DataSourceConfig> shortlistByEmbedding(String userQuery, List<DataSourceConfig> sorted) {
        if (sorted.size() < EMBEDDING_SHORTLIST_THRESHOLD) return sorted;

        List<Integer> candidateIds = routingEmbeddingService
                .findCandidateDatasourceIds(userQuery, EMBEDDING_SHORTLIST_TOPK);
        if (candidateIds.isEmpty()) return sorted;

        List<DataSourceConfig> filtered = sorted.stream()
                .filter(ds -> candidateIds.contains(ds.getId()))
                .toList();
        if (filtered.isEmpty()) return sorted;

        log.debug("Routing shortlisted by embedding: {} → {} candidates", sorted.size(), filtered.size());
        return filtered;
    }

    private String buildRoutingPrompt(String query, List<DataSourceConfig> actives) {
        StringBuilder sb = new StringBuilder();
        sb.append("사용자 질문: ").append(query).append("\n\n");
        sb.append("사용 가능한 데이터소스 목록:\n");
        for (DataSourceConfig ds : actives) {
            List<SqlTableConfig> tables =
                    sqlTableConfigRepository.findByDatasourceIdAndIsActiveTrue(ds.getId());
            sb.append("- ID: ").append(ds.getId())
              .append(", 이름: ").append(ds.getName())
              .append(", 설명: ").append(ds.getDescription() != null ? ds.getDescription() : "(설명 없음)")
              .append(", 테이블: ")
              .append(tables.stream()
                      .map(this::tableLabel)
                      .collect(Collectors.joining(", ")))
              .append("\n");
        }
        sb.append("\n가장 적합한 데이터소스 ID를 JSON으로 반환하세요.");
        return sb.toString();
    }

    /** "테이블명(설명)" — 설명이 있으면 라우팅 힌트로 함께 노출. */
    private String tableLabel(SqlTableConfig t) {
        String desc = t.getDescription();
        return (desc != null && !desc.isBlank())
                ? t.getSourceTable() + "(" + desc + ")"
                : t.getSourceTable();
    }

    private Integer parseRoutingResponse(String response, List<DataSourceConfig> actives) {
        try {
            Pattern p = Pattern.compile("\"datasource_id\"\\s*:\\s*(\\d+)");
            Matcher m = p.matcher(response);
            if (m.find()) {
                int id = Integer.parseInt(m.group(1));
                if (actives.stream().anyMatch(ds -> ds.getId().equals(id))) {
                    log.debug("LLM routed to datasource_id={}", id);
                    return id;
                }
            }
        } catch (Exception e) {
            log.warn("DataSource routing parse failed: {}", e.getMessage());
        }
        log.warn("Routing parse failed or unknown id — falling back to first active datasource");
        return actives.get(0).getId();
    }
}
