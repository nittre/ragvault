package com.ragvault.widget.service;

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



import com.ragvault.core.domain.DataSourceConfig;
import com.ragvault.core.domain.SqlTableConfig;
import com.ragvault.core.prompt.PromptLoader;
import com.ragvault.core.repository.SqlTableConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 사용자 질의 의도 분류 서비스 (축소 이식 — RAG / SQL / HYBRID / REJECT).
 *
 * 활성 datasource 이름·테이블 정보를 프롬프트에 동적 포함.
 * Redis 캐시는 인메모리 ConcurrentHashMap 으로 치환.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentClassifierService {

    private final ChatClient chatClient;
    private final DataSourceConfigService dataSourceConfigService;
    private final SqlTableConfigRepository sqlTableConfigRepository;

    // 인메모리 캐시 (Redis 대체) — key: 질문 + datasource fingerprint
    private final ConcurrentHashMap<String, QueryIntent> intentCache = new ConcurrentHashMap<>();

    private static final String CLASSIFIER_SYSTEM =
            PromptLoader.load("prompts/intent-classifier/system.txt");

    private static final String CLASSIFIER_PROMPT_TEMPLATE =
            PromptLoader.load("prompts/intent-classifier/user-template.txt");

    /** RAG/SQL/HYBRID/REJECT 분류. */
    public QueryIntent classify(String question) {
        List<DataSourceConfig> activeDatasources = fetchAndSortDatasources();
        String fingerprint = toFingerprint(activeDatasources);
        String cacheKey = question + "|ds:" + fingerprint;

        QueryIntent cached = intentCache.get(cacheKey);
        if (cached != null) {
            log.debug("Intent cache hit: '{}' → {}", question, cached);
            return cached;
        }

        String prompt = buildPrompt(question, activeDatasources);
        String response;
        try {
            response = chatClient.prompt()
                    .system(CLASSIFIER_SYSTEM)
                    .user(prompt)
                    .call().content();
        } catch (Exception e) {
            log.error("Intent LLM 분류 실패, RAG fallback", e);
            return QueryIntent.RAG;
        }

        QueryIntent intent = parseIntent(response);
        intentCache.put(cacheKey, intent);
        log.debug("Intent 분류: '{}' → {}", question, intent);
        return intent;
    }

    private List<DataSourceConfig> fetchAndSortDatasources() {
        return dataSourceConfigService.findActiveAll().stream()
                .sorted(Comparator.comparingInt(DataSourceConfig::getId))
                .toList();
    }

    private String toFingerprint(List<DataSourceConfig> datasources) {
        return datasources.stream()
                .map(ds -> String.valueOf(ds.getId()))
                .collect(Collectors.joining(","));
    }

    private String buildPrompt(String question, List<DataSourceConfig> datasources) {
        String datasourceContext;
        if (datasources.isEmpty()) {
            datasourceContext = "";
        } else {
            StringBuilder sb = new StringBuilder("현재 SQL 조회 가능한 데이터소스:\n");
            for (DataSourceConfig ds : datasources) {
                List<SqlTableConfig> tables =
                        sqlTableConfigRepository.findByDatasourceIdAndIsActiveTrue(ds.getId());
                sb.append("- ").append(ds.getName());
                if (ds.getDescription() != null && !ds.getDescription().isBlank())
                    sb.append(" (").append(ds.getDescription()).append(")");
                if (!tables.isEmpty()) {
                    sb.append(": 테이블 [");
                    for (int i = 0; i < tables.size(); i++) {
                        SqlTableConfig t = tables.get(i);
                        String label = (t.getDisplayName() != null && !t.getDisplayName().isBlank())
                                ? t.getDisplayName() : t.getSourceTable();
                        sb.append(label);
                        if (t.getDescription() != null && !t.getDescription().isBlank())
                            sb.append("(").append(t.getDescription()).append(")");
                        if (i < tables.size() - 1) sb.append(", ");
                    }
                    sb.append("]");
                }
                sb.append("\n");
            }
            sb.append("→ 위 데이터소스/테이블의 데이터를 조회하는 질문이면 SQL 또는 HYBRID로 분류하세요.");
            datasourceContext = sb.toString();
        }

        return CLASSIFIER_PROMPT_TEMPLATE
                .replace("{question}", question)
                .replace("{datasource_context}", datasourceContext);
    }

    private QueryIntent parseIntent(String response) {
        if (response == null) return QueryIntent.RAG;
        String u = response.trim().toUpperCase();
        if (u.contains("REJECT")) return QueryIntent.REJECT;
        if (u.contains("HYBRID")) return QueryIntent.HYBRID;
        if (u.contains("SQL"))    return QueryIntent.SQL;
        return QueryIntent.RAG;
    }
}
