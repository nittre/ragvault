package com.ragservice.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;

/**
 * 사용자 질의 의도 분류 서비스 — 6경로.
 *
 * Stage 1 — 정적 규칙 (LLM 호출 없음, 우선순위 순):
 *   IMAGE (images 있음) > FILE (fileIds 있음) > URL_FETCH (URL 패턴)
 *
 * Stage 2 — LLM 분류 (Redis 24h 캐시):
 *   RAG / SQL / HYBRID
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

    private static final String CLASSIFIER_SYSTEM =
            "당신은 질문 분류기입니다. 반드시 RAG, SQL, HYBRID 중 정확히 하나의 단어만 출력하세요.";

    private static final String CLASSIFIER_PROMPT =
            """
            사용자 질문을 다음 3가지 중 하나로 분류하세요:
            - RAG: 문서/교재/매뉴얼에서 개념·설명·방법·현황을 찾는 질문. 기술 개념 설명, 학습 자료, 커리큘럼 내용, 부트캠프 일정·상태 등.
            - SQL: 특정 데이터베이스에서 수치·건수·목록을 집계·조회하는 질문. "몇 명", "총액", "평균", "목록 조회" 등.
            - HYBRID: 수치 조회와 문서 설명이 모두 필요한 질문.

            중요: SQL, Python, Java 같은 기술 용어가 포함되어도 개념/원리/사용법을 묻는 질문이면 반드시 RAG입니다.

            예시:
            질문: "JavaScript 클로저란?" → RAG
            질문: "SQL JOIN 종류와 차이점 설명해줘" → RAG
            질문: "Python 데코레이터 사용법" → RAG
            질문: "3기 부트캠프 진행 상태는?" → HYBRID
            질문: "부트캠프 커리큘럼 내용 알려줘" → RAG
            질문: "수강생이 총 몇 명이에요?" → SQL
            질문: "지난달 매출 총액은?" → SQL
            질문: "보증 만료된 고객 수와 보증 정책은?" → HYBRID

            질문: {question}
            """;

    private static final Duration CACHE_TTL = Duration.ofHours(24);

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
        // Stage 1: 정적 규칙
        if (images != null && !images.isEmpty()) return QueryIntent.IMAGE;
        if (fileIds != null && !fileIds.isEmpty()) return QueryIntent.FILE;
        if (containsUrl(question)) return QueryIntent.URL_FETCH;

        // Stage 2: LLM (RAG/SQL/HYBRID)
        return classifyByLlm(question);
    }

    private boolean containsUrl(String text) {
        if (text == null) return false;
        return text.contains("http://") || text.contains("https://");
    }

    private QueryIntent classifyByLlm(String question) {
        String cacheKey = "intent:" + sha256Prefix(question);
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("Intent cache hit: {} → {}", cacheKey, cached);
            return parseIntent(cached);
        }
        String prompt = CLASSIFIER_PROMPT.replace("{question}", question);
        String response;
        try {
            response = chatClient.prompt()
                    .system(CLASSIFIER_SYSTEM)
                    .user(prompt)
                    .call().content();
        } catch (Exception e) {
            log.error("Intent LLM 분류 실패, RAG fallback", e);
            return QueryIntent.RAG;
        }
        QueryIntent intent = parseIntent(response);
        try {
            redisTemplate.opsForValue().set(cacheKey, intent.name(), CACHE_TTL);
        } catch (Exception e) {
            log.warn("Intent 캐시 쓰기 실패 (non-fatal)", e);
        }
        log.debug("Intent 분류: '{}' → {}", question, intent);
        return intent;
    }

    private QueryIntent parseIntent(String response) {
        if (response == null) return QueryIntent.RAG;
        String u = response.trim().toUpperCase();
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
