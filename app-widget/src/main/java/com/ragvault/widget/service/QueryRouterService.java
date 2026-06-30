package com.ragvault.widget.service;

import com.ragvault.core.service.SchemaInspectorService;
import com.ragvault.core.service.DataSourceConfigService;
import com.ragvault.core.service.RagTableConfigService;
import com.ragvault.core.service.SensitivityAnalysisService;
import com.ragvault.core.service.RagColumnSuggestionService;
import com.ragvault.core.service.SqlGeneratorService;
import com.ragvault.core.service.DataSourceRouterService;
import com.ragvault.core.service.RoutingEmbeddingService;
import com.ragvault.core.service.QueryIntent;

import com.ragvault.core.domain.DocumentChunk;
import com.ragvault.widget.service.WidgetRagService.HistoryMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 의도 분류 결과에 따라 RAG / SQL / HYBRID / REJECT 로 분기하는 라우터 (축소 이식).
 *
 * ragvault 에는 멀티모달/URL/FILE/WEB 경로가 없으므로 제거.
 * RAG 경로는 기존 WidgetRagService 를 재사용한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRouterService {

    private final IntentClassifierService intentClassifier;
    private final WidgetRagService widgetRagService;
    private final TextToSqlService textToSqlService;

    public record RouterResult(
            String content,
            List<DocumentChunk.ChunkResult> sources,
            String intent,
            String responseId,
            boolean blocked,
            String generatedSql
    ) {
        static RouterResult fromRag(WidgetRagService.RagResult r) {
            return new RouterResult(r.content(), r.sources(), "RAG", null, r.blocked(), null);
        }
        static RouterResult fromSql(TextToSqlService.SqlQueryResult r) {
            return new RouterResult(r.content(), List.of(), r.intent(), r.responseId(), r.denied(), r.generatedSql());
        }
    }

    /**
     * 의도 분류 후 라우팅.
     *
     * @param userMessage 사용자 질문
     * @param history     대화 이력 (RAG 경로 전용)
     * @param userEmail   사용자 이메일 (감사 로그용)
     */
    public RouterResult route(String userMessage, List<HistoryMessage> history, String userEmail) {
        QueryIntent intent = intentClassifier.classify(userMessage);
        log.debug("Intent: '{}' → {}", userMessage, intent);

        return switch (intent) {
            case REJECT -> new RouterResult(
                    "요청을 처리할 수 없습니다. 데이터 조회 관련 질문으로 다시 시도해주세요. (err_rejected)",
                    List.of(), "REJECT", null, true, null);

            case SQL -> RouterResult.fromSql(textToSqlService.query(userMessage, userEmail));

            case HYBRID -> {
                // HYBRID: SQL 우선. 결과가 있으면 SQL, 없으면 RAG 폴백.
                TextToSqlService.SqlQueryResult sql = textToSqlService.query(userMessage, userEmail);
                if (!sql.denied() && sql.hasRows()) {
                    yield new RouterResult(sql.content(), List.of(), "HYBRID",
                            sql.responseId(), false, sql.generatedSql());
                }
                log.debug("HYBRID: SQL 결과 없음 → RAG 폴백");
                WidgetRagService.RagResult rag = widgetRagService.chat(userMessage, history);
                yield new RouterResult(rag.content(), rag.sources(), "HYBRID",
                        null, rag.blocked(), null);
            }

            case RAG -> RouterResult.fromRag(widgetRagService.chat(userMessage, history));

            // widget 은 멀티모달/URL/FILE/WEB 경로를 지원하지 않으므로 RAG 로 폴백
            case URL_FETCH, FILE, IMAGE, IMAGE_RAG, WEB_SEARCH ->
                    RouterResult.fromRag(widgetRagService.chat(userMessage, history));
        };
    }
}
