package com.ragservice.rag.service;

import com.ragservice.rag.domain.DocumentChunk;
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
    private final ResponseRawStorageService rawStorage;
    private final RagMetricsService metricsService;

    @Value("${rag.mysql.model:qwen2.5:7b-instruct-q4_K_M}")
    private String llmModel;

    public record RouterResult(
            String content,
            List<DocumentChunk.ChunkResult> sources,
            String intent,
            String responseId,
            boolean blocked,
            String generatedSql
    ) {
        static RouterResult fromRag(RagService.RagResult r, String responseId) {
            return new RouterResult(r.content(), r.sources(), "RAG", responseId, r.blocked(), null);
        }
        static RouterResult fromSql(TextToSqlService.SqlQueryResult r) {
            return new RouterResult(r.content(), List.of(), r.intent(), r.responseId(), r.denied(), r.generatedSql());
        }
        static RouterResult fromHybrid(HybridQueryService.HybridResult r) {
            return new RouterResult(r.content(), List.of(), r.intent(), r.responseId(), false, null);
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
                case SQL -> RouterResult.fromSql(
                        textToSqlService.query(userMessage, userEmail));

                case HYBRID -> RouterResult.fromHybrid(
                        hybridQueryService.query(userMessage, history, userEmail));

                case URL_FETCH -> {
                    String url = extractUrl(userMessage);
                    UrlFetchService.UrlFetchResult r = urlFetchService.fetch(url, userMessage, userEmail);
                    yield new RouterResult(r.content(), List.of(), "URL_FETCH",
                            r.responseId(), r.denied(), null);
                }

                case FILE -> {
                    FileContextService.FileQueryResult r =
                            fileContextService.query(userMessage, fileIds, userEmail);
                    yield new RouterResult(r.content(), List.of(), "FILE",
                            r.responseId(), r.error(), null);
                }

                case IMAGE -> {
                    ImagePathService.ImageResult r =
                            imagePathService.analyze(userMessage, images, userEmail);
                    yield new RouterResult(r.content(), List.of(), "IMAGE",
                            r.responseId(), false, null);
                }

                case RAG -> {
                    RagService.RagResult rag = ragService.chat(userMessage, history);
                    String responseId = rawStorage.store(rag.content(), "RAG", userEmail, llmModel);
                    yield RouterResult.fromRag(rag, responseId);
                }
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

    private String extractUrl(String text) {
        if (text == null) return "";
        for (String word : text.split("\\s+")) {
            if (word.startsWith("http://") || word.startsWith("https://")) return word;
        }
        return text;
    }
}
