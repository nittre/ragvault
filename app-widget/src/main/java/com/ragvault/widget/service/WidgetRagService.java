package com.ragvault.widget.service;

import com.ragvault.core.domain.DocumentChunk.ChunkResult;
import com.ragvault.core.prompt.PromptLoader;
import com.ragvault.core.repository.DocumentChunkRepository;
import com.ragvault.widget.security.InputValidator;
import com.ragvault.core.security.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 위젯 RAG 핵심 서비스.
 *
 * 처리 흐름:
 * 1. 입력 검증 (InputValidator — prompt injection 차단)
 * 2. 임베딩 생성 (OllamaEmbeddingModel)
 * 3. pgvector 코사인 유사도 검색
 * 4. 유사도 임계 미만 → fallback 응답 (환각 방지)
 * 5. 컨텍스트 포맷팅
 * 6. LLM 호출 (ChatClient — Spring AI)
 * 7. PII 마스킹 (ADR-0008)
 *
 * SQL/멀티모달/파일/URL 경로 없음 — 지식문서 RAG 전용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WidgetRagService {

    private final ChatClient chatClient;
    private final OllamaEmbeddingModel embeddingModel;
    private final DocumentChunkRepository chunkRepository;
    private final PiiMasker piiMasker;
    private final InputValidator inputValidator;
    private final ConversationLogService conversationLogService;
    private final SearchConfigService searchConfigService;

    private static final String SYSTEM_PROMPT =
            PromptLoader.load("prompts/widget-rag-service/system.txt");

    private static final String QUERY_REWRITE_SYSTEM =
            PromptLoader.load("prompts/query-rewrite/system.txt");

    /**
     * RAG 질의응답 — 기존 2인자 오버로드 (테스트 호환 유지).
     */
    public RagResult chat(String userMessage, List<HistoryMessage> history) {
        return chat(userMessage, history, "unknown", null, "RAG");
    }

    /**
     * RAG 질의응답 — 기존 4인자 오버로드 (테스트/기존 호출부 호환 유지).
     */
    public RagResult chat(String userMessage, List<HistoryMessage> history, String sessionId, String siteKey) {
        return chat(userMessage, history, sessionId, siteKey, "RAG");
    }

    /**
     * RAG 질의응답.
     *
     * @param userMessage 사용자 질문
     * @param history     대화 이력 (최근 N턴)
     * @param sessionId   세션 ID (로그 기록용)
     * @param siteKey     사이트 키 (로그 기록용, nullable)
     * @param action      대화 로그 라우팅 분류 (RAG/HYBRID/OTHER — QueryRouterService 가 어떤 경로로 이 메서드를 호출했는지)
     * @return RagResult (마스킹된 응답 + 출처 청크)
     */
    public RagResult chat(String userMessage, List<HistoryMessage> history, String sessionId, String siteKey,
                           String action) {
        // 검색 파라미터 실시간 조회 (SearchConfigService — DB 우선, fallback 포함)
        int topK = searchConfigService.getTopK();
        double threshold = searchConfigService.getThreshold();
        String noResultsResp = searchConfigService.getNoResultsResponse();
        String injectionBlockedResp = searchConfigService.getInjectionBlockedResponse();

        // 1. 입력 검증
        InputValidator.ValidationResult validation = inputValidator.validate(userMessage);
        if (!validation.valid()) {
            log.warn("Input validation failed: {}", validation.reason());
            RagResult result = RagResult.blocked(injectionBlockedResp);
            conversationLogService.saveAsync(sessionId, siteKey, userMessage,
                    injectionBlockedResp, true, false, 0, action);
            return result;
        }

        // 2. 검색 쿼리 재작성 — 대화 이력이 있으면 후속 질문을 독립형 질문으로 재작성해
        //    "좀 더 자세히" 같은 지시어만 있는 후속 질문도 검색이 되도록 한다.
        String retrievalQuery = history.isEmpty()
                ? userMessage
                : rewriteStandaloneQuery(userMessage, history);

        // 3. 임베딩
        float[] embedding = embeddingModel.embed(retrievalQuery);
        String embeddingJson = toJsonArray(embedding);

        // 4. pgvector 검색 — 후속 질문은 "더 자세히" 요청일 수 있으므로 topK를 늘려
        //    답변 근거로 쓸 수 있는 청크를 더 확보한다 (동일 topK로는 첫 턴과 같은 청크만
        //    다시 뽑혀 "더 자세히" 요청에도 내용이 늘어나지 않는 문제가 있었다).
        int searchTopK = history.isEmpty() ? topK : Math.min(topK * 2, 20);
        List<Object[]> rows = chunkRepository.findSimilarChunks(embeddingJson, threshold, searchTopK);
        List<ChunkResult> chunks = rows.stream()
                .map(r -> new ChunkResult(
                        (String) r[0],
                        (String) r[1],
                        (String) r[2],
                        ((Number) r[3]).doubleValue()))
                .toList();

        // 5. 청크 없으면 fallback — 환각 방지
        if (chunks.isEmpty()) {
            log.debug("No chunks found for query '{}' (original: '{}'), returning fallback response",
                    retrievalQuery, userMessage);
            RagResult result = RagResult.noContext(noResultsResp);
            conversationLogService.saveAsync(sessionId, siteKey, userMessage,
                    noResultsResp, false, false, 0, action);
            return result;
        }

        // 6. 컨텍스트 포맷팅
        String context = formatChunks(chunks);

        // 7. LLM 호출 — 사용자에게 실제로 한 말 그대로(userMessage) 전달
        String fullPrompt = buildPrompt(context, history, userMessage);
        log.debug("Calling LLM with {} chunks, history={}", chunks.size(), history.size());

        String llmResponse = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(fullPrompt)
                .call()
                .content();

        // 8. PII 마스킹 (ADR-0008: 모든 LLM 응답 경로에 적용)
        String maskedResponse = piiMasker.mask(llmResponse);

        // 9. 대화 로그 비동기 저장
        conversationLogService.saveAsync(sessionId, siteKey, userMessage,
                maskedResponse, false, true, chunks.size(), action);

        return RagResult.success(maskedResponse, chunks);
    }

    /**
     * 대화 이력을 참고해 후속 질문을 검색에 적합한 독립형(standalone) 질문으로 재작성한다.
     * 재작성 LLM 호출이 실패하거나 빈 응답을 반환하면 원본 질문으로 폴백한다(fail-open) —
     * 검색 품질 개선이 검색 자체를 막아서는 안 된다.
     */
    private String rewriteStandaloneQuery(String userMessage, List<HistoryMessage> history) {
        StringBuilder sb = new StringBuilder("[대화 이력]\n");
        history.forEach(m -> sb.append(m.role()).append(": ").append(m.content()).append("\n"));
        sb.append("\n[후속 질문]\n").append(userMessage).append("\n\n")
          .append("위 후속 질문을 대화 이력 맥락을 반영해 그 자체로 이해 가능한 완전한 독립형 질문 " +
                  "하나로 재작성하세요. 재작성된 질문 문장만 출력하고, 질문에 답하지는 마세요.");

        try {
            String rewritten = chatClient.prompt()
                    .system(QUERY_REWRITE_SYSTEM)
                    .user(sb.toString())
                    .call()
                    .content();
            if (rewritten == null || rewritten.isBlank()) {
                return userMessage;
            }
            log.debug("Rewrote follow-up query: '{}' -> '{}'", userMessage, rewritten.trim());
            return rewritten.trim();
        } catch (Exception e) {
            log.warn("Query rewrite failed, falling back to original message", e);
            return userMessage;
        }
    }

    private String formatChunks(List<ChunkResult> chunks) {
        return IntStream.range(0, chunks.size())
                .mapToObj(i -> {
                    ChunkResult c = chunks.get(i);
                    return String.format("[%d] %s (출처: %s)", i + 1, c.content(), c.sourceId());
                })
                .collect(Collectors.joining("\n"));
    }

    private String buildPrompt(String context, List<HistoryMessage> history, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("[참고자료]\n").append(context).append("\n\n");
        if (!history.isEmpty()) {
            sb.append("[대화 이력]\n");
            history.forEach(m -> sb.append(m.role()).append(": ").append(m.content()).append("\n"));
            sb.append("\n");
            // 후속 질문은 "간결하게 답변하라"는 기본 규칙과 "더 자세히 설명해달라"는 사용자
            // 의도가 충돌하기 쉽다. 이전 답변을 반복하지 말고 참고자료를 더 폭넓게 활용해
            // 답변 깊이를 확장하도록 명시적으로 안내한다.
            sb.append("[안내]\n이 질문은 위 대화의 후속 질문입니다. 이전 답변을 그대로 반복하지 말고, " +
                    "[참고자료]의 내용 중 이전 답변에서 다루지 않은 부분까지 포함해 더 구체적이고 " +
                    "깊이 있게 답변하세요.\n\n");
        }
        sb.append("[현재 질문]\n").append(userMessage);
        return sb.toString();
    }

    private String toJsonArray(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * RAG 처리 결과.
     */
    public record RagResult(String content, List<ChunkResult> sources, boolean blocked) {

        public static RagResult success(String content, List<ChunkResult> sources) {
            return new RagResult(content, sources, false);
        }

        public static RagResult noContext(String content) {
            return new RagResult(content, List.of(), false);
        }

        public static RagResult blocked(String content) {
            return new RagResult(content, List.of(), true);
        }
    }

    /**
     * 대화 이력 메시지.
     */
    public record HistoryMessage(String role, String content) {}
}
