package com.ragservice.rag.service;

import com.ragvault.core.domain.DocumentChunk.ChunkResult;
import com.ragservice.rag.dto.MessageDto;
import com.ragvault.core.prompt.PromptLoader;
import com.ragvault.core.repository.DocumentChunkRepository;
import com.ragservice.rag.security.InputValidator;
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
 * RAG 핵심 서비스.
 *
 * 처리 흐름:
 * 1. 입력 검증 (InputValidator)
 * 2. 임베딩 생성 (OllamaEmbeddingModel — Spring AI auto-config)
 * 3. pgvector 코사인 유사도 검색 (ADR-0002)
 * 4. 컨텍스트 포맷팅
 * 5. LLM 호출 (ChatClient — Spring AI, ADR-0004)
 * 6. PII 마스킹 (ADR-0008)
 *
 * requirements/04-rag-search-strategy.md
 * requirements/05-prompt-design.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final ChatClient chatClient;
    private final OllamaEmbeddingModel embeddingModel;
    private final DocumentChunkRepository chunkRepository;
    private final PiiMasker piiMasker;
    private final InputValidator inputValidator;

    private static final String SYSTEM_PROMPT =
            PromptLoader.load("prompts/rag-service/system.txt");

    private static final String QUERY_REWRITE_SYSTEM =
            PromptLoader.load("prompts/query-rewrite/system.txt");

    @Value("${rag.search.default-top-k:5}")
    private int defaultTopK;

    @Value("${rag.search.default-threshold:0.65}")
    private double defaultThreshold;

    @Value("${rag.prompts.no-results-response}")
    private String noResultsResponse;

    @Value("${rag.prompts.insufficient-context-warning}")
    private String insufficientContextWarning;

    @Value("${rag.prompts.injection-blocked-response}")
    private String injectionBlockedResponse;

    /**
     * 동기 RAG 질의응답.
     *
     * @param userMessage 사용자 질문
     * @param history     대화 이력 (최대 10턴)
     * @return RagResult (마스킹된 응답 + 출처 청크)
     */
    public RagResult chat(String userMessage, List<MessageDto> history) {
        // 1. 입력 검증
        InputValidator.ValidationResult validation = inputValidator.validate(userMessage);
        if (!validation.valid()) {
            log.warn("Input validation failed: {}", validation.reason());
            return RagResult.blocked(injectionBlockedResponse);
        }

        // 2. 검색 쿼리 재작성 — 대화 이력이 있으면 후속 질문을 독립형 질문으로 재작성해
        //    "좀 더 자세히" 같은 지시어만 있는 후속 질문도 검색이 되도록 한다.
        String retrievalQuery = history.isEmpty()
                ? userMessage
                : rewriteStandaloneQuery(userMessage, history);

        // 3. 임베딩 (bge-m3: 한국어/다국어 특화, 접두어 없이 그대로 사용)
        float[] embedding = embeddingModel.embed(retrievalQuery);
        String embeddingJson = toJsonArray(embedding);

        // 4. pgvector 검색 — 후속 질문은 "더 자세히" 요청일 수 있으므로 topK를 늘려
        //    답변 근거로 쓸 수 있는 청크를 더 확보한다 (동일 topK로는 첫 턴과 같은 청크만
        //    다시 뽑혀 "더 자세히" 요청에도 내용이 늘어나지 않는 문제가 있었다).
        int searchTopK = history.isEmpty() ? defaultTopK : Math.min(defaultTopK * 2, 20);
        List<Object[]> rows = chunkRepository.findSimilarChunks(embeddingJson, defaultThreshold, searchTopK);
        List<ChunkResult> chunks = rows.stream()
                .map(r -> new ChunkResult(
                        (String) r[0],
                        (String) r[1],
                        (String) r[2],
                        ((Number) r[3]).doubleValue()))
                .toList();

        // 5. 청크 없으면 LLM 호출 생략
        if (chunks.isEmpty()) {
            log.debug("No chunks found for query '{}' (original: '{}'), returning no-results response",
                    retrievalQuery, userMessage);
            return RagResult.noContext(noResultsResponse);
        }

        // 6. 컨텍스트 포맷팅
        String context = formatChunks(chunks);
        String contextWarning = chunks.size() <= 2 ? insufficientContextWarning : "";

        // 7. LLM 호출 (Spring AI ChatClient) — 사용자에게 실제로 한 말 그대로(userMessage) 전달
        String fullPrompt = buildPrompt(context, contextWarning, history, userMessage);
        log.debug("Calling LLM with {} chunks, history size={}", chunks.size(), history.size());

        String llmResponse = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(fullPrompt)
                .call()
                .content();

        // 8. PII 마스킹 (ADR-0008: 모든 LLM 응답 경로에 적용)
        String maskedResponse = piiMasker.mask(llmResponse);

        return RagResult.success(maskedResponse, chunks);
    }

    /**
     * 대화 이력을 참고해 후속 질문을 검색에 적합한 독립형(standalone) 질문으로 재작성한다.
     * 재작성 LLM 호출이 실패하거나 빈 응답을 반환하면 원본 질문으로 폴백한다(fail-open) —
     * 검색 품질 개선이 검색 자체를 막아서는 안 된다.
     */
    private String rewriteStandaloneQuery(String userMessage, List<MessageDto> history) {
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
                    return String.format("[%d] %s (%s#%s)", i + 1, c.content(), c.sourceTable(), c.sourceId());
                })
                .collect(Collectors.joining("\n"));
    }

    private String buildPrompt(String context, String contextWarning,
                               List<MessageDto> history, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("[참고자료]\n").append(context).append("\n\n");
        if (!contextWarning.isBlank()) {
            sb.append(contextWarning).append("\n\n");
        }
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

        static RagResult success(String content, List<ChunkResult> sources) {
            return new RagResult(content, sources, false);
        }

        static RagResult noContext(String content) {
            return new RagResult(content, List.of(), false);
        }

        static RagResult blocked(String content) {
            return new RagResult(content, List.of(), true);
        }
    }
}
