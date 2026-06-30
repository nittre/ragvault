package com.ragvault.widget.service;

import com.ragvault.core.domain.DocumentChunk.ChunkResult;
import com.ragvault.core.policy.AccessPolicy;
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
 * 3. pgvector 코사인 유사도 검색 (access_groups && ARRAY['all'])
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
    private final AccessPolicy accessPolicy;

    @Value("${widget.prompts.system}")
    private String systemPrompt;

    /**
     * RAG 질의응답 — 기존 2인자 오버로드 (테스트 호환 유지).
     */
    public RagResult chat(String userMessage, List<HistoryMessage> history) {
        return chat(userMessage, history, "unknown", null);
    }

    /**
     * RAG 질의응답.
     *
     * @param userMessage 사용자 질문
     * @param history     대화 이력 (최근 N턴)
     * @param sessionId   세션 ID (로그 기록용)
     * @param siteKey     사이트 키 (로그 기록용, nullable)
     * @return RagResult (마스킹된 응답 + 출처 청크)
     */
    public RagResult chat(String userMessage, List<HistoryMessage> history, String sessionId, String siteKey) {
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
                    injectionBlockedResp, true, false, 0);
            return result;
        }

        // 2. 임베딩
        float[] embedding = embeddingModel.embed(userMessage);
        String embeddingJson = toJsonArray(embedding);

        // 3. pgvector 검색 (AccessPolicy 기반 access_groups 필터)
        List<Object[]> rows = chunkRepository.findSimilarChunks(embeddingJson, threshold, topK, accessPolicy.allowedAccessGroups());
        List<ChunkResult> chunks = rows.stream()
                .map(r -> new ChunkResult(
                        (String) r[0],
                        (String) r[1],
                        (String) r[2],
                        ((Number) r[3]).doubleValue()))
                .toList();

        // 4. 청크 없으면 fallback — 환각 방지
        if (chunks.isEmpty()) {
            log.debug("No chunks found, returning fallback response");
            RagResult result = RagResult.noContext(noResultsResp);
            conversationLogService.saveAsync(sessionId, siteKey, userMessage,
                    noResultsResp, false, false, 0);
            return result;
        }

        // 5. 컨텍스트 포맷팅
        String context = formatChunks(chunks);

        // 6. LLM 호출
        String fullPrompt = buildPrompt(context, history, userMessage);
        log.debug("Calling LLM with {} chunks, history={}", chunks.size(), history.size());

        String llmResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(fullPrompt)
                .call()
                .content();

        // 7. PII 마스킹 (ADR-0008: 모든 LLM 응답 경로에 적용)
        String maskedResponse = piiMasker.mask(llmResponse);

        // 8. 대화 로그 비동기 저장
        conversationLogService.saveAsync(sessionId, siteKey, userMessage,
                maskedResponse, false, true, chunks.size());

        return RagResult.success(maskedResponse, chunks);
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
