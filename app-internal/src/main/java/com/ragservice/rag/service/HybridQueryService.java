package com.ragservice.rag.service;

import com.ragservice.rag.dto.EffectiveParams;
import com.ragservice.rag.dto.MessageDto;
import com.ragvault.core.prompt.PromptLoader;
import com.ragvault.core.security.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaOptions;
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
     * @param question        사용자 질문
     * @param history         대화 이력
     * @param userEmail       사용자 이메일 (감사 로그용)
     * @param effectiveParams ADR-0005 7단계 우선순위 체인이 계산한 최종 파라미터
     */
    public HybridResult query(String question, List<MessageDto> history, String userEmail,
                               EffectiveParams effectiveParams) {
        // RAG, SQL, (선택) WEB_SEARCH 병렬 실행
        CompletableFuture<RagService.RagResult> ragFuture =
                CompletableFuture.supplyAsync(() -> ragService.chat(question, history, effectiveParams));

        CompletableFuture<TextToSqlService.SqlQueryResult> sqlFuture =
                CompletableFuture.supplyAsync(() -> textToSqlService.query(question, userEmail, effectiveParams));

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

        String hybridStyle = extractString(effectiveParams, "hybrid_synthesis_style");
        String synthesisPrompt = buildSynthesisPrompt(question, ragContent, sqlContent, webContent, hybridStyle);

        double temperature = extractDouble(effectiveParams, "temperature");
        double topP = extractDouble(effectiveParams, "top_p");
        int maxTokens = extractInt(effectiveParams, "max_tokens");

        String rawResponse = chatClient.prompt()
                .system(SYNTHESIS_SYSTEM)
                .user(synthesisPrompt)
                .options(OllamaOptions.builder()
                        .temperature(temperature)
                        .topP(topP)
                        .numPredict(maxTokens)
                        .build())
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

    /**
     * @param hybridStyle BALANCED(동등 취급) | SQL_FIRST(DB 결과 우선, 기존 하드코딩 동작) | RAG_FIRST(문서 결과 우선)
     *                    우선순위 안내 문구는 해당 섹션이 실제로 프롬프트에 포함돼 있을 때만 붙인다 —
     *                    존재하지 않는 섹션을 근거로 삼으라고 지시하면 LLM이 혼란스러워한다.
     */
    private String buildSynthesisPrompt(String question, String ragContent, String sqlContent, String webContent,
                                         String hybridStyle) {
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
        sb.append("위 결과들을 하나의 답변으로 통합하세요. ");
        if ("SQL_FIRST".equals(hybridStyle) && sqlContent != null) {
            sb.append("대주제·하위주제·콘텐츠는 [데이터베이스 조회 결과]만 근거로 제시하고, ")
              .append(ragContent != null ? "[문서 검색 결과]는 개념 보충 설명에만 사용하세요." : "");
        } else if ("RAG_FIRST".equals(hybridStyle) && ragContent != null) {
            sb.append("[문서 검색 결과]를 우선 근거로 삼아 설명하고, ")
              .append(sqlContent != null ? "[데이터베이스 조회 결과]는 구체적인 수치·목록을 보강하는 데만 사용하세요." : "");
        } else {
            sb.append("각 결과의 출처를 명시하며 동등하게 종합하세요.");
        }
        return sb.toString();
    }

    /**
     * ADR-0005: 서버 코드에는 폴백 값을 두지 않는다 — Stage 1이 이미 모든 파라미터의
     * default_value 설정을 강제하므로, 값이 없거나 타입이 안 맞으면 즉시 예외를 던진다.
     */
    private String extractString(EffectiveParams effectiveParams, String key) {
        Object value = effectiveParams.values().get(key);
        if (!(value instanceof String s)) {
            throw new IllegalStateException("파라미터 '" + key + "'가 EffectiveParams에 없거나 문자열이 아닙니다.");
        }
        return s;
    }

    private double extractDouble(EffectiveParams effectiveParams, String key) {
        Object value = effectiveParams.values().get(key);
        if (!(value instanceof Number n)) {
            throw new IllegalStateException("파라미터 '" + key + "'가 EffectiveParams에 없거나 숫자가 아닙니다.");
        }
        return n.doubleValue();
    }

    private int extractInt(EffectiveParams effectiveParams, String key) {
        Object value = effectiveParams.values().get(key);
        if (!(value instanceof Number n)) {
            throw new IllegalStateException("파라미터 '" + key + "'가 EffectiveParams에 없거나 숫자가 아닙니다.");
        }
        return n.intValue();
    }

    private <T> T getSafely(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            return null;
        }
    }
}
