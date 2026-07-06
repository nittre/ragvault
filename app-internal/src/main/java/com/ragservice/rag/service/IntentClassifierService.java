package com.ragservice.rag.service;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 사용자 질의 의도 분류 서비스 — 6경로.
 *
 * Stage 1 — 정적 규칙 (LLM 호출 없음, 우선순위 순):
 *   IMAGE (images 있음) > FILE (fileIds 있음) > URL_FETCH (URL 패턴)
 *
 * Stage 2 — LLM 분류 (Redis 24h 캐시):
 *   RAG / SQL / HYBRID
 *   프롬프트에 활성 datasource 이름·테이블 정보를 동적 포함.
 *   캐시 키 = sha256(질문 + datasource fingerprint) — datasource 변경 시 자동 무효화.
 *
 * requirements/10-multimodal-files-url.md 섹션 2
 * requirements/08-text-to-sql.md 섹션 3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentClassifierService {

    private final ChatClient chatClient;
    private final RedisTemplate<String, String> redisTemplate;
    private final DataSourceConfigService dataSourceConfigService;
    private final SqlTableConfigRepository sqlTableConfigRepository;

    @Value("${rag.web-search.enabled:true}")
    private boolean webSearchEnabled;

    private static final String CLASSIFIER_SYSTEM =
            PromptLoader.load("prompts/intent-classifier/system.txt");

    private static final String CLASSIFIER_SYSTEM_WITH_IMAGE =
            PromptLoader.load("prompts/intent-classifier/system-with-image.txt");

    private static final String CLASSIFIER_PROMPT_TEMPLATE =
            PromptLoader.load("prompts/intent-classifier/user-template.txt");

    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final String DS_FINGERPRINT_KEY = "ds:fingerprint";
    private static final Duration FP_TTL = Duration.ofMinutes(1);

    /** 하위 호환성 유지 — images/fileIds 없을 때 */
    public QueryIntent classify(String question) {
        return classify(question, null, null);
    }

    /**
     * 6경로 분류.
     * @param images  base64 이미지 목록 (null/empty = 없음)
     * @param fileIds 파일 ID 목록 (null/empty = 없음)
     */
    public QueryIntent classify(String question, List<String> images, List<String> fileIds) {
        // Stage 1: 정적 규칙 (LLM 호출 없음)
        // IMAGE_RAG 분기는 호출자가 classifyEnrichedForImageRag() 로 2-Phase 수행
        if (images != null && !images.isEmpty()) return QueryIntent.IMAGE;
        if (fileIds != null && !fileIds.isEmpty()) return QueryIntent.FILE;
        if (containsUrl(question)) return QueryIntent.URL_FETCH;

        // Stage 2: LLM (RAG/SQL/HYBRID)
        return classifyByLlm(question);
    }

    /**
     * IMAGE_RAG Phase 2 전용 — 이미지 분석 결과가 합쳐진 enriched query로 의도 재분류.
     *
     * 이미지 분석은 이미 끝났으므로 IMAGE 후보를 두지 않고(hasImages=false)
     * RAG/SQL/HYBRID/WEB_SEARCH/REJECT 중 하나로 분류한다.
     * enriched query에 이미지에서 추출된 개념(예: HTML/CSS/JavaScript)이 포함되므로,
     * datasource 컨텍스트(커리큘럼 테이블 등)와 결합해 SQL/HYBRID 분기를 정확히 탄다.
     */
    public QueryIntent classifyEnrichedForImageRag(String enrichedQuery) {
        return classifyByLlm(enrichedQuery, false);
    }

    private boolean containsUrl(String text) {
        if (text == null) return false;
        return text.contains("http://") || text.contains("https://");
    }

    private QueryIntent classifyByLlm(String question) {
        return classifyByLlm(question, false);
    }

    private QueryIntent classifyByLlm(String question, boolean hasImages) {
        // #2 fix: fingerprint를 Redis(1분)에서 먼저 확인 — 캐시 히트 시 DB 조회 없음
        String dsFingerprint = getCachedFingerprint();
        String imgSuffix = hasImages ? "|img:1" : "";
        String cacheKey = "intent:" + sha256Prefix(question + "|ds:" + dsFingerprint + imgSuffix);

        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("Intent cache hit: {} → {}", cacheKey, cached);
            return parseIntent(cached, hasImages);
        }

        // 캐시 미스 → full DB 조회
        List<DataSourceConfig> activeDatasources = fetchAndSortDatasources();
        String freshFingerprint = toFingerprint(activeDatasources);

        // datasource 구성이 바뀌었으면 fingerprint·cacheKey 갱신
        if (!freshFingerprint.equals(dsFingerprint)) {
            try {
                redisTemplate.opsForValue().set(DS_FINGERPRINT_KEY, freshFingerprint, FP_TTL);
            } catch (Exception e) {
                log.warn("Fingerprint 캐시 갱신 실패 (non-fatal)", e);
            }
            cacheKey = "intent:" + sha256Prefix(question + "|ds:" + freshFingerprint + imgSuffix);
        }

        String prompt = buildPrompt(question, activeDatasources, hasImages);
        String systemPrompt = hasImages ? CLASSIFIER_SYSTEM_WITH_IMAGE : CLASSIFIER_SYSTEM;
        String response;
        try {
            response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(prompt)
                    .call().content();
        } catch (Exception e) {
            log.error("Intent LLM 분류 실패, RAG fallback", e);
            return QueryIntent.RAG;
        }

        QueryIntent intent = parseIntent(response, hasImages);
        try {
            redisTemplate.opsForValue().set(cacheKey, intent.name(), CACHE_TTL);
        } catch (Exception e) {
            log.warn("Intent 캐시 쓰기 실패 (non-fatal)", e);
        }
        log.debug("Intent 분류: '{}' → {}", question, intent);
        return intent;
    }

    /** Redis(1분 TTL) → DB 순으로 fingerprint 반환. Redis 미스 시 DB 결과를 캐시에 저장. */
    private String getCachedFingerprint() {
        try {
            String fp = redisTemplate.opsForValue().get(DS_FINGERPRINT_KEY);
            if (fp != null) return fp;
        } catch (Exception e) {
            log.warn("Fingerprint 캐시 조회 실패 (non-fatal)", e);
        }
        String fp = toFingerprint(fetchAndSortDatasources());
        try {
            redisTemplate.opsForValue().set(DS_FINGERPRINT_KEY, fp, FP_TTL);
        } catch (Exception e) {
            log.warn("Fingerprint 캐시 저장 실패 (non-fatal)", e);
        }
        return fp;
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

    /**
     * 활성 datasource 목록을 프롬프트에 동적으로 포함.
     * datasource가 없으면 해당 섹션 생략.
     */
    private String buildPrompt(String question, List<DataSourceConfig> datasources) {
        return buildPrompt(question, datasources, false);
    }

    private String buildPrompt(String question, List<DataSourceConfig> datasources, boolean hasImages) {
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
                    // #8 fix: displayName·description 포함 → 어휘 갭 축소
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

        String imageContext = hasImages
                ? """
                  [이미지 첨부됨] 사용자가 이미지를 첨부하여 질문했습니다.

                  IMAGE를 선택하는 조건 (둘 다 충족해야 함):
                    1. 이미지 자체를 분석·설명·묘사하는 것이 질문의 전부일 때
                    2. 내부 DB, 내부 문서, 외부 웹 검색이 추가로 필요하지 않을 때
                  IMAGE 예시: "이 이미지 분석해줘", "이미지 설명해줘", "무슨 내용이야?", "어떤 그림이야?" → IMAGE

                  이미지와 함께 내부 DB/문서 조회 또는 외부 검색도 필요하면 IMAGE가 아니라 RAG/SQL/HYBRID/WEB_SEARCH 중 하나를 선택하세요.
                  비IMAGE 예시:
                    "이 이미지 보고 우리 DB에 관련 컨텐츠 있는지 찾아줘" → RAG 또는 HYBRID
                    "이미지 속 제품이 우리 재고 DB에 있어?" → SQL
                    "이 화면 오류를 최신 공식 문서에서 찾아줘" → WEB_SEARCH

                  """
                : "";

        // #1 fix: {question}을 먼저 치환해 datasourceContext 내 {question} 문자열이
        //         사용자 질문으로 오염되는 것을 방지
        return imageContext + CLASSIFIER_PROMPT_TEMPLATE
                .replace("{question}", question)
                .replace("{datasource_context}", datasourceContext);
    }

    private QueryIntent parseIntent(String response) {
        return parseIntent(response, false);
    }

    private QueryIntent parseIntent(String response, boolean hasImages) {
        if (response == null) return QueryIntent.RAG;
        String u = response.trim().toUpperCase();
        if (u.contains("REJECT")) return QueryIntent.REJECT;  // 두뇌 가드레일 — 다른 분기보다 우선
        if (hasImages && u.contains("IMAGE")) return QueryIntent.IMAGE;
        if (u.contains("WEB"))    return webSearchEnabled ? QueryIntent.WEB_SEARCH : QueryIntent.RAG;
        if (u.contains("HYBRID")) return QueryIntent.HYBRID;
        if (u.contains("SQL"))    return QueryIntent.SQL;
        return QueryIntent.RAG;
    }

    private String sha256Prefix(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }
}
