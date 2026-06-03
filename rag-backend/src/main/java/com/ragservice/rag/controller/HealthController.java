package com.ragservice.rag.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 헬스 체크 컨트롤러.
 *
 * GET /api/v1/health       — Liveness (ALB 헬스체크용, 항상 200)
 * GET /api/v1/health/deep  — Deep readiness (DB·Redis·Ollama 연결 확인)
 *
 * requirements/06-error-handling.md 섹션 6 참조.
 * /status 페이지에서 deep health 결과를 폴링함.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "service", "rag-backend"));
    }

    /**
     * Deep Health — DB·Redis·Ollama 개별 상태 확인.
     * 하나라도 DOWN이면 503 반환.
     * ALB 헬스체크에는 사용하지 않는다 (별도 /health 경로).
     */
    @GetMapping("/health/deep")
    public ResponseEntity<Map<String, Object>> deepHealth() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("service", "rag-backend");

        boolean allUp = true;

        // PostgreSQL (pgvector)
        Map<String, Object> postgres = new LinkedHashMap<>();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            postgres.put("status", "UP");
        } catch (Exception e) {
            postgres.put("status", "DOWN");
            postgres.put("error", truncate(e.getMessage()));
            allUp = false;
        }
        result.put("postgres", postgres);

        // Redis
        Map<String, Object> redis = new LinkedHashMap<>();
        try {
            String pong = redisTemplate.getConnectionFactory()
                    .getConnection().ping();
            redis.put("status", "PONG".equalsIgnoreCase(pong) ? "UP" : "DEGRADED");
        } catch (Exception e) {
            redis.put("status", "DOWN");
            redis.put("error", truncate(e.getMessage()));
            allUp = false;
        }
        result.put("redis", redis);

        // Ollama (HTTP GET /api/tags)
        Map<String, Object> ollama = new LinkedHashMap<>();
        try {
            var url = new java.net.URL(ollamaBaseUrl + "/api/tags");
            var conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            ollama.put("status", code == 200 ? "UP" : "DEGRADED");
            ollama.put("httpStatus", code);
        } catch (Exception e) {
            ollama.put("status", "DOWN");
            ollama.put("error", truncate(e.getMessage()));
            allUp = false;
        }
        result.put("ollama", ollama);

        result.put("overall", allUp ? "UP" : "DOWN");

        return allUp
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(503).body(result);
    }

    /** 에러 메시지 상세 스택 노출 방지 — 80자 이하로 잘라냄 */
    private String truncate(String msg) {
        if (msg == null) return "unknown error";
        return msg.length() > 80 ? msg.substring(0, 80) + "..." : msg;
    }
}
