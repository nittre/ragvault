package com.ragservice.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragservice.rag.dto.EffectiveParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ParameterResolver 결과 Redis 캐시.
 *
 * 키 형식: param:{userEmail}:{conversationId}
 * TTL: 5분 (300초)
 *
 * ADR-0005: 파라미터 7단계 우선순위 체인 캐싱 전략.
 * - conversationId null/blank → 캐시 스킵 (새 대화 첫 메시지 충돌 방지)
 * - evictByUser: param:{userEmail}:* 패턴 scan + delete
 * - evictAll: param:* 패턴 scan + delete
 * - scan 커서 방식 사용 (운영 환경 KEYS 명령 금지)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParameterCacheService {

    private static final Duration TTL = Duration.ofSeconds(300L);
    private static final String KEY_PREFIX = "param:";
    private static final int SCAN_COUNT = 100;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 캐시에서 EffectiveParams 조회.
     *
     * @param userEmail      사용자 이메일 (null 이면 empty 반환)
     * @param conversationId 대화 ID (null/blank 이면 empty 반환 — 캐시 스킵)
     * @return 캐시 히트 시 EffectiveParams, 미스 시 empty
     */
    public Optional<EffectiveParams> get(String userEmail, String conversationId) {
        if (!isCacheable(userEmail, conversationId)) {
            return Optional.empty();
        }
        String key = cacheKey(userEmail, conversationId);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            EffectiveParams params = objectMapper.readValue(json, EffectiveParams.class);
            log.debug("Parameter cache hit: key={}", key);
            return Optional.of(params);
        } catch (JsonProcessingException e) {
            log.warn("Parameter cache deserialization failed: key={}", key, e);
            return Optional.empty();
        }
    }

    /**
     * 캐시에 EffectiveParams 저장.
     * conversationId null/blank 이면 저장하지 않는다.
     */
    public void put(String userEmail, String conversationId, EffectiveParams params) {
        if (!isCacheable(userEmail, conversationId)) {
            return;
        }
        String key = cacheKey(userEmail, conversationId);
        try {
            String json = objectMapper.writeValueAsString(params);
            redisTemplate.opsForValue().set(key, json, TTL);
            log.debug("Parameter cache stored: key={}", key);
        } catch (JsonProcessingException e) {
            log.warn("Parameter cache serialization failed: key={}", key, e);
        }
    }

    /**
     * 특정 사용자 캐시 무효화 (사용자 프로필 변경 시).
     * param:{userEmail}:* 패턴 scan + delete.
     */
    public void evictByUser(String userEmail) {
        if (userEmail == null || userEmail.isBlank()) {
            return;
        }
        String pattern = KEY_PREFIX + userEmail + ":*";
        long deleted = scanAndDelete(pattern);
        log.info("Parameter cache evicted by user: userEmail={}, deleted={}", userEmail, deleted);
    }

    /**
     * 특정 대화 캐시 무효화 (대화별 override 변경 시).
     */
    public void evictByConversation(String userEmail, String conversationId) {
        if (!isCacheable(userEmail, conversationId)) {
            return;
        }
        String key = cacheKey(userEmail, conversationId);
        Boolean deleted = redisTemplate.delete(key);
        log.info("Parameter cache evicted by conversation: key={}, deleted={}", key, deleted);
    }

    /**
     * 전체 파라미터 캐시 무효화 (관리자 Guard A/B 변경 시).
     * param:* 패턴 scan + delete.
     */
    public void evictAll() {
        long deleted = scanAndDelete(KEY_PREFIX + "*");
        log.info("Parameter cache evicted all: deleted={}", deleted);
    }

    private String cacheKey(String userEmail, String conversationId) {
        return KEY_PREFIX + userEmail + ":" + conversationId;
    }

    private boolean isCacheable(String userEmail, String conversationId) {
        return userEmail != null && !userEmail.isBlank()
                && conversationId != null && !conversationId.isBlank();
    }

    /**
     * scan 커서 방식으로 패턴에 해당하는 키를 찾아 삭제한다.
     * KEYS 명령 대신 SCAN 사용 — 운영 환경 블로킹 방지.
     *
     * @param pattern Redis key 패턴 (glob style)
     * @return 삭제된 키 수
     */
    private long scanAndDelete(String pattern) {
        ScanOptions opts = ScanOptions.scanOptions()
                .match(pattern)
                .count(SCAN_COUNT)
                .build();

        List<String> keysToDelete = new ArrayList<>();

        redisTemplate.execute((RedisConnection connection) -> {
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(opts)) {
                cursor.forEachRemaining(key -> keysToDelete.add(new String(key)));
            }
            return null;
        });

        if (keysToDelete.isEmpty()) {
            return 0L;
        }
        Long count = redisTemplate.delete(keysToDelete);
        return count != null ? count : 0L;
    }
}
