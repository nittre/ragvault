package com.ragservice.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR-0010: PII 마스킹 실패 진단을 위한 LLM 원본 응답 단기 저장소.
 *
 * 모든 LLM 응답(RAG/SQL/HYBRID)은 piiMasker.mask() 호출 전에 Redis에 저장.
 * TTL = 30분. admin api:incident-response scope 로만 조회 가능.
 *
 * key 형식: resp_raw:{16자 UUID hex}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResponseRawStorageService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final Duration TTL = Duration.ofMinutes(30);

    /**
     * 원본 응답 저장 후 responseId 반환.
     * piiMasker.mask() 호출 전에 반드시 호출해야 함 (ADR-0010).
     *
     * @return responseId (Redis key 전체, "resp_raw:{hex}" 형식)
     */
    public String store(String rawResponse, String intent, String userEmail, String llmModel) {
        String responseId = "resp_raw:" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            Map<String, String> payload = Map.of(
                    "response", rawResponse != null ? rawResponse : "",
                    "intent",   intent     != null ? intent     : "UNKNOWN",
                    "user_email", userEmail != null ? userEmail : "",
                    "model",   llmModel   != null ? llmModel   : "",
                    "ts",      Instant.now().toString()
            );
            redisTemplate.opsForValue().set(responseId, objectMapper.writeValueAsString(payload), TTL);
        } catch (Exception e) {
            log.error("ResponseRawStorage write error for responseId={}", responseId, e);
        }
        return responseId;
    }

    /**
     * admin 원본 조회. api:incident-response scope 필요 (SecurityConfig 에서 강제).
     *
     * @param responseId Redis key 전체 ("resp_raw:{hex}")
     */
    public Optional<String> retrieve(String responseId) {
        String val = redisTemplate.opsForValue().get(responseId);
        if (val == null) return Optional.empty();
        try {
            Map<?, ?> map = objectMapper.readValue(val, Map.class);
            return Optional.ofNullable((String) map.get("response"));
        } catch (Exception e) {
            log.error("ResponseRawStorage read error for responseId={}", responseId, e);
            return Optional.empty();
        }
    }
}
