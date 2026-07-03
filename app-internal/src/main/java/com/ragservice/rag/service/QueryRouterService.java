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

import com.ragvault.core.domain.DocumentChunk;
import com.ragservice.rag.dto.MessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 의도 분류 결과에 따라 6경로로 분기하는 라우터.
 *
 * RAG / SQL / HYBRID / URL_FETCH / FILE / IMAGE
 *
 * ADR-0008: 모든 경로에 PiiMasker 적용 (각 서비스 내부에서 처리)
 * ADR-0010: 모든 경로에 ResponseRawStorageService 적용 (각 서비스 내부에서 처리)
 *
 * requirements/08-text-to-sql.md, requirements/10-multimodal-files-url.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRouterService {

    private final IntentClassifierService intentClassifier;
    private final RagService ragService;
    private final TextToSqlService textToSqlService;
    private final HybridQueryService hybridQueryService;
    private final UrlFetchService urlFetchService;
    private final FileContextService fileContextService;
    private final ImagePathService imagePathService;
    private final WebSearchService webSearchService;
    private final ResponseRawStorageService rawStorage;
    private final RagMetricsService metricsService;

    @Value("${spring.ai.ollama.chat.options.model:qwen2.5:7b-instruct-q4_K_M}")
    private String llmModel;

    @Value("${rag.web-search.enabled:true}")
    private boolean webSearchEnabled;

    public record RouterResult(
            String content,
            List<DocumentChunk.ChunkResult> sources,
            String intent,
            String responseId,
            boolean blocked,
            String generatedSql,
            List<String> sourceUrls
    ) {
        static RouterResult fromRag(RagService.RagResult r, String responseId) {
            return new RouterResult(r.content(), r.sources(), "RAG", responseId, r.blocked(), null, List.of());
        }
        static RouterResult fromSql(TextToSqlService.SqlQueryResult r) {
            return new RouterResult(r.content(), List.of(), r.intent(), r.responseId(), r.denied(), r.generatedSql(), List.of());
        }
        static RouterResult fromHybrid(HybridQueryService.HybridResult r) {
            return new RouterResult(r.content(), List.of(), r.intent(), r.responseId(), false, null, r.sourceUrls());
        }
    }

    /**
     * 슬래시 커맨드 강제 라우팅.
     *
     * FORCE_RAG  → RAG 우선, sources 없으면 WEB_SEARCH 폴백
     * FORCE_WEB  → WEB_SEARCH 우선, denied 이면 RAG 폴백
     * null       → 기존 intentClassifier 경로
     */
    public RouterResult route(String userMessage, List<MessageDto> history, String userEmail,
                               List<String> images, List<String> fileIds, String routingHint) {
        // 메시지 자체의 슬래시 커맨드 파싱 (프론트엔드 미지원 클라이언트·브라우저 캐시 대응)
        String hint = routingHint;
        String cleanMessage = userMessage;
        if (hint == null && userMessage != null) {
            if (userMessage.startsWith("/web ")) {
                hint = "FORCE_WEB";
                cleanMessage = userMessage.substring(5).stripLeading();
            } else if (userMessage.equals("/web")) {
                hint = "FORCE_WEB";
                cleanMessage = "";
            } else if (userMessage.startsWith("/rag ")) {
                hint = "FORCE_RAG";
                cleanMessage = userMessage.substring(5).stripLeading();
            } else if (userMessage.equals("/rag")) {
                hint = "FORCE_RAG";
                cleanMessage = "";
            } else if (userMessage.startsWith("/sql ")) {
                hint = "FORCE_SQL";
                cleanMessage = userMessage.substring(5).stripLeading();
            } else if (userMessage.equals("/sql")) {
                hint = "FORCE_SQL";
                cleanMessage = "";
            }
        }

        if ("FORCE_RAG".equals(hint)) {
            return routeForceRag(cleanMessage, history, userEmail, images, fileIds);
        }
        if ("FORCE_WEB".equals(hint)) {
            return routeForceWeb(cleanMessage, userEmail, history, images, fileIds);
        }
        if ("FORCE_SQL".equals(hint)) {
            return routeForceSql(cleanMessage, userEmail);
        }
        return route(userMessage, history, userEmail, images, fileIds);
    }

    private RouterResult routeForceRag(String userMessage, List<MessageDto> history, String userEmail,
                                        List<String> images, List<String> fileIds) {
        log.debug("FORCE_RAG: '{}'", userMessage);
        long startMs = System.currentTimeMillis();
        String intentName = "RAG";
        RouterResult result;
        try {
            if (images != null && !images.isEmpty()) {
                intentName = "IMAGE";
                ImagePathService.ImageResult r = imagePathService.analyze(userMessage, images, userEmail);
                result = new RouterResult(r.content(), List.of(), "IMAGE", r.responseId(), false, null, List.of());
            } else if (fileIds != null && !fileIds.isEmpty()) {
                intentName = "FILE";
                FileContextService.FileQueryResult r = fileContextService.query(userMessage, fileIds, userEmail);
                result = new RouterResult(r.content(), List.of(), "FILE", r.responseId(), r.error(), null, List.of());
            } else {
                RagService.RagResult rag = ragService.chat(userMessage, history);
                if (!rag.sources().isEmpty()) {
                    String responseId = rawStorage.store(rag.content(), "RAG", userEmail, llmModel);
                    result = RouterResult.fromRag(rag, responseId);
                } else {
                    // RAG 결과 없음 → WEB_SEARCH 폴백
                    log.debug("FORCE_RAG 폴백 → WEB_SEARCH: '{}'", userMessage);
                    intentName = "WEB_SEARCH";
                    WebSearchService.WebSearchResult web = webSearchService.search(userMessage, userEmail);
                    result = new RouterResult(web.content(), List.of(), "WEB_SEARCH",
                            web.responseId(), web.denied(), null, web.sourceUrls());
                }
            }
        } catch (Exception e) {
            metricsService.incrementError(intentName);
            throw e;
        }
        metricsService.incrementQuery(intentName);
        metricsService.recordQueryDuration(intentName, System.currentTimeMillis() - startMs);
        if (result.blocked()) {
            metricsService.incrementBlocked(intentName.toLowerCase());
        }
        return result;
    }

    private RouterResult routeForceWeb(String userMessage, String userEmail, List<MessageDto> history,
                                        List<String> images, List<String> fileIds) {
        log.debug("FORCE_WEB: '{}'", userMessage);
        long startMs = System.currentTimeMillis();
        String intentName = "WEB_SEARCH";
        RouterResult result;
        try {
            WebSearchService.WebSearchResult web = webSearchService.search(userMessage, userEmail);
            if (!web.denied()) {
                result = new RouterResult(web.content(), List.of(), "WEB_SEARCH",
                        web.responseId(), false, null, web.sourceUrls());
            } else {
                // WEB_SEARCH 실패 → RAG 폴백
                log.debug("FORCE_WEB 폴백 → RAG: '{}'", userMessage);
                intentName = "RAG";
                RagService.RagResult rag = ragService.chat(userMessage, history);
                String responseId = rawStorage.store(rag.content(), "RAG", userEmail, llmModel);
                result = RouterResult.fromRag(rag, responseId);
            }
        } catch (Exception e) {
            metricsService.incrementError(intentName);
            throw e;
        }
        metricsService.incrementQuery(intentName);
        metricsService.recordQueryDuration(intentName, System.currentTimeMillis() - startMs);
        if (result.blocked()) {
            metricsService.incrementBlocked(intentName.toLowerCase());
        }
        return result;
    }

    private RouterResult routeForceSql(String userMessage, String userEmail) {
        log.debug("FORCE_SQL: '{}'", userMessage);
        long startMs = System.currentTimeMillis();
        try {
            RouterResult result = RouterResult.fromSql(textToSqlService.query(userMessage, userEmail));
            metricsService.incrementQuery("SQL");
            metricsService.recordQueryDuration("SQL", System.currentTimeMillis() - startMs);
            if (result.blocked()) metricsService.incrementBlocked("sql");
            return result;
        } catch (Exception e) {
            metricsService.incrementError("SQL");
            throw e;
        }
    }

    /**
     * 6경로 라우팅.
     *
     * @param images  base64 이미지 목록 (IMAGE 경로)
     * @param fileIds 파일 ID 목록 (FILE 경로)
     */
    public RouterResult route(String userMessage, List<MessageDto> history, String userEmail,
                               List<String> images, List<String> fileIds) {
        QueryIntent intent = intentClassifier.classify(userMessage, images, fileIds);
        log.debug("Intent: '{}' → {}", userMessage, intent);

        long startMs = System.currentTimeMillis();
        RouterResult result;

        try {
            result = switch (intent) {
                case REJECT -> new RouterResult(
                        "요청을 처리할 수 없습니다. 데이터 조회 관련 질문으로 다시 시도해주세요. (err_rejected)",
                        List.of(), "REJECT", null, true, null, List.of());

                case SQL -> RouterResult.fromSql(
                        textToSqlService.query(userMessage, userEmail));

                case HYBRID -> RouterResult.fromHybrid(
                        hybridQueryService.query(userMessage, history, userEmail));

                case URL_FETCH -> {
                    String url = extractUrl(userMessage);
                    UrlFetchService.UrlFetchResult r = urlFetchService.fetch(url, userMessage, userEmail);
                    yield new RouterResult(r.content(), List.of(), "URL_FETCH",
                            r.responseId(), r.denied(), null, List.of());
                }

                case FILE -> {
                    FileContextService.FileQueryResult r =
                            fileContextService.query(userMessage, fileIds, userEmail);
                    yield new RouterResult(r.content(), List.of(), "FILE",
                            r.responseId(), r.error(), null, List.of());
                }

                case IMAGE -> {
                    ImagePathService.ImageResult r =
                            imagePathService.analyze(userMessage, images, userEmail);
                    yield new RouterResult(r.content(), List.of(), "IMAGE",
                            r.responseId(), false, null, List.of());
                }

                case IMAGE_RAG -> {
                    // Phase 1: VLM 이미지 분석 + 핵심 키워드 추출
                    ImagePathService.ImageRagResult imgResult =
                            imagePathService.analyzeWithKeywords(userMessage, images, userEmail);

                    // VLM 실패(responseId == null) 시 Phase 2 진입 없이 에러 응답 반환
                    if (imgResult.responseId() == null) {
                        yield new RouterResult(imgResult.content(), List.of(), "IMAGE_RAG",
                                null, true, null, List.of());
                    }

                    // 이미지에서 추출한 '핵심 개념'으로 compact enriched query 구성.
                    // VLM 분석 본문을 응답에 노출하지 않고(환각·블록 분리 방지) 라우팅/검색 입력으로만 쓴다.
                    // 키워드 추출 실패 시에만 원본 본문으로 폴백.
                    String concepts = (imgResult.keywords() != null && !imgResult.keywords().isBlank())
                            ? imgResult.keywords() : imgResult.content();
                    String enrichedQuery = userMessage + "\n\n[이미지에서 식별된 핵심 개념]\n" + concepts;

                    QueryIntent textIntent = intentClassifier.classifyEnrichedForImageRag(enrichedQuery);
                    log.debug("IMAGE_RAG Phase 2 intent: {}", textIntent);

                    RouterResult secondary;
                    if (textIntent == QueryIntent.HYBRID) {
                        // HYBRID(DB+문서)는 DB를 권위 출처로 삼아 SQL 우선 실행.
                        // RAG 문서검색을 함께 종합하면 일반론이 DB 결과(대주제·하위주제)를
                        // 덮어써 환각을 유발하므로, SQL 결과가 있으면 SQL만 쓰고 없을 때만 RAG로 폴백한다.
                        TextToSqlService.SqlQueryResult sql =
                                textToSqlService.query(enrichedQuery, userEmail);
                        if (!sql.denied() && sql.hasRows()) {
                            log.debug("IMAGE_RAG: HYBRID → SQL 사용");
                            secondary = RouterResult.fromSql(sql);
                        } else {
                            // SQL 생성/실행 실패(denied) 또는 결과 0행 → 문서검색(RAG)으로 폴백
                            log.debug("IMAGE_RAG: SQL 결과 없음(denied={}, hasRows={}) → RAG 폴백",
                                    sql.denied(), sql.hasRows());
                            secondary = routeTextOnly(QueryIntent.RAG, enrichedQuery, history, userEmail, fileIds);
                        }
                    } else {
                        // RAG / SQL / WEB_SEARCH / URL_FETCH / REJECT 는 분류대로 처리.
                        // (문서검색이 필요한 RAG 질의는 그대로 RAG 경로로 처리됨)
                        secondary = routeTextOnly(textIntent, enrichedQuery, history, userEmail, fileIds);
                    }

                    // 이미지 에세이를 붙이지 않고 통합 답변(secondary)만 반환. 이미지 개념은
                    // enrichedQuery(→ 종합 프롬프트의 [질문])에 이미 반영돼 있다.
                    yield new RouterResult(secondary.content(), secondary.sources(), "IMAGE_RAG",
                            secondary.responseId(), secondary.blocked(), secondary.generatedSql(),
                            secondary.sourceUrls());
                }

                case WEB_SEARCH -> {
                    WebSearchService.WebSearchResult r =
                            webSearchService.search(userMessage, userEmail);
                    yield new RouterResult(r.content(), List.of(), "WEB_SEARCH",
                            r.responseId(), r.denied(), null, r.sourceUrls());
                }

                case RAG -> ragWithWebFallback(userMessage, history, userEmail);
            };
        } catch (Exception e) {
            metricsService.incrementError(intent.name());
            throw e;
        }

        metricsService.incrementQuery(intent.name());
        metricsService.recordQueryDuration(intent.name(), System.currentTimeMillis() - startMs);
        if (result.blocked()) {
            metricsService.incrementBlocked(intent.name().toLowerCase());
        }

        return result;
    }

    /** 하위 호환성 유지 — images/fileIds 없는 경우 */
    public RouterResult route(String userMessage, List<MessageDto> history, String userEmail) {
        return route(userMessage, history, userEmail, null, null);
    }

    /** IMAGE_RAG Phase 2 — 이미지 설명이 enriched된 질문으로 텍스트 경로만 실행 */
    private RouterResult routeTextOnly(QueryIntent intent, String question,
                                       List<MessageDto> history, String userEmail,
                                       List<String> fileIds) {
        return switch (intent) {
            case REJECT -> new RouterResult(
                    "요청을 처리할 수 없습니다. 데이터 조회 관련 질문으로 다시 시도해주세요. (err_rejected)",
                    List.of(), "REJECT", null, true, null, List.of());
            case SQL -> RouterResult.fromSql(textToSqlService.query(question, userEmail));
            case HYBRID -> RouterResult.fromHybrid(hybridQueryService.query(question, history, userEmail));
            case WEB_SEARCH -> {
                WebSearchService.WebSearchResult r = webSearchService.search(question, userEmail);
                yield new RouterResult(r.content(), List.of(), "WEB_SEARCH",
                        r.responseId(), r.denied(), null, r.sourceUrls());
            }
            case URL_FETCH -> {
                String url = extractUrl(question);
                UrlFetchService.UrlFetchResult r = urlFetchService.fetch(url, question, userEmail);
                yield new RouterResult(r.content(), List.of(), "URL_FETCH",
                        r.responseId(), r.denied(), null, List.of());
            }
            default -> ragWithWebFallback(question, history, userEmail);
        };
    }

    /**
     * RAG 검색 결과가 없으면 WEB_SEARCH로 폴백한다.
     *
     * routeForceRag()의 "/rag" 강제 명령 폴백과 동일한 동작을, 자동 분류로 RAG가 선택된
     * 일반 경로에도 적용한다 — RAG로 시작한 대화의 후속 질문(예: 답변 중 등장한 용어를
     * 되묻는 질문)이 내부 문서에 없을 때도 실패 응답 대신 웹 검색으로 이어지게 한다.
     * rag.web-search.enabled=false 이면 폴백하지 않는다.
     */
    private RouterResult ragWithWebFallback(String query, List<MessageDto> history, String userEmail) {
        RagService.RagResult rag = ragService.chat(query, history);
        if (!rag.sources().isEmpty() || !webSearchEnabled) {
            String responseId = rawStorage.store(rag.content(), "RAG", userEmail, llmModel);
            return RouterResult.fromRag(rag, responseId);
        }
        log.debug("RAG 결과 없음 → WEB_SEARCH 폴백: '{}'", query);
        WebSearchService.WebSearchResult web = webSearchService.search(query, userEmail);
        return new RouterResult(web.content(), List.of(), "WEB_SEARCH",
                web.responseId(), web.denied(), null, web.sourceUrls());
    }

    private String extractUrl(String text) {
        if (text == null) return "";
        for (String word : text.split("\\s+")) {
            if (word.startsWith("http://") || word.startsWith("https://")) return word;
        }
        return text;
    }
}
