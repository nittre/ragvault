package com.ragservice.rag.service;

import com.ragvault.core.domain.DocumentChunk.ChunkResult;
import com.ragvault.core.policy.AccessPolicy;
import com.ragservice.rag.dto.MessageDto;
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
 * 3. pgvector 코사인 유사도 검색 (access_groups && ARRAY['all'], ADR-0002)
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
    private final AccessPolicy accessPolicy;

    @Value("${rag.prompts.system}")
    private String systemPrompt;

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

        // 2. 임베딩 (bge-m3: 한국어/다국어 특화, 접두어 없이 그대로 사용)
        float[] embedding = embeddingModel.embed(userMessage);
        String embeddingJson = toJsonArray(embedding);

        // 3. pgvector 검색 (AccessPolicy 기반 access_groups 필터)
        List<Object[]> rows = chunkRepository.findSimilarChunks(embeddingJson, defaultThreshold, defaultTopK, accessPolicy.allowedAccessGroups());
        List<ChunkResult> chunks = rows.stream()
                .map(r -> new ChunkResult(
                        (String) r[0],
                        (String) r[1],
                        (String) r[2],
                        ((Number) r[3]).doubleValue()))
                .toList();

        // 4. 청크 없으면 LLM 호출 생략
        if (chunks.isEmpty()) {
            log.debug("No chunks found for query, returning no-results response");
            return RagResult.noContext(noResultsResponse);
        }

        // 5. 컨텍스트 포맷팅
        String context = formatChunks(chunks);
        String contextWarning = chunks.size() <= 2 ? insufficientContextWarning : "";

        // 6. LLM 호출 (Spring AI ChatClient)
        String fullPrompt = buildPrompt(context, contextWarning, history, userMessage);
        log.debug("Calling LLM with {} chunks, history size={}", chunks.size(), history.size());

        String llmResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(fullPrompt)
                .call()
                .content();

        // 7. PII 마스킹 (ADR-0008: 모든 LLM 응답 경로에 적용)
        String maskedResponse = piiMasker.mask(llmResponse);

        return RagResult.success(maskedResponse, chunks);
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
