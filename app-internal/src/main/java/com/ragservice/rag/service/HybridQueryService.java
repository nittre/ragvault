package com.ragservice.rag.service;

import com.ragservice.rag.dto.MessageDto;
import com.ragvault.core.prompt.PromptLoader;
import com.ragvault.core.security.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RAG + SQL 병렬 실행 후 종합하는 Hybrid 경로 서비스.
 *
 * 처리 흐름:
 * 1. CompletableFuture.allOf() 로 RAG + SQL 병렬 실행 (15초 타임아웃)
 * 2. 완료된 결과만 수집 (타임아웃 시 부분 결과 사용)
 * 3. 종합 LLM 호출 (ChatClient)
 * 4. 원본 저장 (ResponseRawStorageService — ADR-0010, PiiMasker 전!)
 * 5. PII 마스킹 (ADR-0008)
 *
 * requirements/08-text-to-sql.md
 * ADR-0008: 모든 LLM 응답 경로에 PII 마스킹
 * ADR-0010: 원본 응답 Short-lived Storage
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridQueryService {

    private final RagService ragService;
    private final TextToSqlService textToSqlService;
    private final ChatClient chatClient;
    private final PiiMasker piiMasker;
    private final ResponseRawStorageService rawStorage;
    private final WebSearchService webSearchService;

    @Value("${spring.ai.ollama.chat.options.model:qwen2.5:7b-instruct-q4_K_M}")
    private String llmModel;

    @Value("${rag.hybrid.timeout-sec:120}")
    private int hybridTimeoutSec;

    @Value("${rag.web-search.hybrid-enabled:false}")
    private boolean webSearchInHybrid;

    private static final String SYNTHESIS_SYSTEM =
            PromptLoader.load("prompts/hybrid-query-synthesis/system.txt");

    public record HybridResult(String content, String intent, String responseId, List<String> sourceUrls) {}

    /**
     * RAG + SQL 병렬 실행 후 종합 응답 반환.
     *
     * @param question  사용자 질문
     * @param history   대화 이력
     * @param userEmail 사용자 이메일 (감사 로그용)
     */
    public HybridResult query(String question, List<MessageDto> history, String userEmail) {
        // RAG, SQL, (선택) WEB_SEARCH 병렬 실행
        CompletableFuture<RagService.RagResult> ragFuture =
                CompletableFuture.supplyAsync(() -> ragService.chat(question, history));

        CompletableFuture<TextToSqlService.SqlQueryResult> sqlFuture =
                CompletableFuture.supplyAsync(() -> textToSqlService.query(question, userEmail));

        CompletableFuture<WebSearchService.WebSearchResult> webFuture =
                webSearchInHybrid
                        ? CompletableFuture.supplyAsync(() -> webSearchService.search(question, userEmail))
                        : CompletableFuture.completedFuture(null);

        RagService.RagResult ragResult = null;
        TextToSqlService.SqlQueryResult sqlResult = null;
        WebSearchService.WebSearchResult webResult = null;

        try {
            CompletableFuture.allOf(ragFuture, sqlFuture, webFuture)
                    .get(hybridTimeoutSec, TimeUnit.SECONDS);
            ragResult = ragFuture.get();
            sqlResult = sqlFuture.get();
            webResult = webFuture.get();
        } catch (TimeoutException e) {
            log.warn("Hybrid query timed out after {}s, using partial results", hybridTimeoutSec);
            ragResult  = ragFuture.isDone()  ? getSafely(ragFuture)  : null;
            sqlResult  = sqlFuture.isDone()  ? getSafely(sqlFuture)  : null;
            webResult  = webFuture.isDone()  ? getSafely(webFuture)  : null;
        } catch (Exception e) {
            log.error("Hybrid query parallel execution error", e);
            ragResult  = ragFuture.isDone()  ? getSafely(ragFuture)  : null;
            sqlResult  = sqlFuture.isDone()  ? getSafely(sqlFuture)  : null;
            webResult  = webFuture.isDone()  ? getSafely(webFuture)  : null;
        }

        // 종합 프롬프트 구성
        String ragContent = ragResult != null ? ragResult.content() : null;
        String sqlContent = (sqlResult != null && !sqlResult.denied()) ? sqlResult.content() : null;
        String webContent = (webResult != null && !webResult.denied()) ? webResult.content() : null;

        String synthesisPrompt = buildSynthesisPrompt(question, ragContent, sqlContent, webContent);

        String rawResponse = chatClient.prompt()
                .system(SYNTHESIS_SYSTEM)
                .user(synthesisPrompt)
                .call()
                .content();

        // ADR-0010: 원본 저장 (PiiMasker 전에 반드시 호출)
        String responseId = rawStorage.store(rawResponse, "HYBRID", userEmail, llmModel);

        // ADR-0008: PII 마스킹
        String masked = piiMasker.mask(rawResponse);

        List<String> sourceUrls = (webResult != null && !webResult.denied())
                ? webResult.sourceUrls() : List.of();
        return new HybridResult(masked, "HYBRID", responseId, sourceUrls);
    }

    private String buildSynthesisPrompt(String question, String ragContent, String sqlContent, String webContent) {
        if (ragContent == null && sqlContent == null && webContent == null) {
            return question + "\n\n관련 정보를 찾을 수 없습니다.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[질문]\n").append(question).append("\n\n");
        if (ragContent != null) {
            sb.append("[문서 검색 결과]\n").append(ragContent).append("\n\n");
        }
        if (sqlContent != null) {
            sb.append("[데이터베이스 조회 결과]\n").append(sqlContent).append("\n\n");
        }
        if (webContent != null) {
            sb.append("[웹 검색 결과]\n").append(webContent).append("\n\n");
        }
        sb.append("위 결과들을 하나의 답변으로 통합하세요. ")
          .append("대주제·하위주제·콘텐츠는 [데이터베이스 조회 결과]만 근거로 제시하고, ")
          .append("[문서 검색 결과]는 개념 보충 설명에만 사용하세요.");
        return sb.toString();
    }

    private <T> T getSafely(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            return null;
        }
    }
}
