package com.ragservice.rag.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragservice.rag.domain.ApiKey;
import com.ragservice.rag.repository.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import java.util.concurrent.CompletableFuture;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * API Key Bearer 토큰 검증 필터.
 *
 * 검증 순서:
 * 1. Authorization: Bearer sk-rag-{...} 파싱
 * 2. 앞 15자를 key_prefix로 DB 후보 조회
 * 3. BCrypt 검증
 * 4. 만료 검사
 * 5. Scope 검증 (/v1/chat/** → api:chat 필요)
 * 6. Rate Limiting (Redis): 60/min, 1000/hour, 10000/day
 * 7. SecurityContextHolder 설정
 * 8. last_used_at 비동기 업데이트
 *
 * requirements/07-auth-security.md
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int KEY_PREFIX_LENGTH = 15;

    // Rate limit 창별 설정: {TTL(초), limit}
    private static final long RATE_MINUTE_TTL = 60L;
    private static final long RATE_MINUTE_LIMIT = 60L;
    private static final long RATE_HOUR_TTL = 3600L;
    private static final long RATE_HOUR_LIMIT = 1000L;
    private static final long RATE_DAY_TTL = 86400L;
    private static final long RATE_DAY_LIMIT = 10000L;

    private final ApiKeyRepository apiKeyRepository;
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.startsWith("/api/v1/health") || path.startsWith("/actuator")) {
            return true;
        }
        // ADR-0011: 로그인·로그아웃은 공개. change-password 는 JWT 인증 필요 → 여기서 skip 안 함
        // (JwtAuthFilter 가 먼저 인증하므로 아래 existing auth 체크에서 skip 됨)
        if (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/logout")) {
            return true;
        }
        // ADR-0011: SPA 정적 자산 — /api/ 또는 /v1/ 로 시작하지 않는 경로는 API Key 검증 불필요
        if (!path.startsWith("/api/") && !path.startsWith("/v1/")) {
            return true;
        }
        // JwtAuthFilter 가 이미 인증한 경우 Bearer 검증 생략
        var existing = SecurityContextHolder.getContext().getAuthentication();
        return existing != null && existing.isAuthenticated();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            sendError(response, HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
            return;
        }

        String rawKey = authHeader.substring(BEARER_PREFIX.length()).trim();

        // key_prefix: 앞 15자
        if (rawKey.length() < KEY_PREFIX_LENGTH) {
            sendError(response, HttpStatus.UNAUTHORIZED, "Invalid API key format");
            return;
        }
        String prefix = rawKey.substring(0, KEY_PREFIX_LENGTH);

        // DB에서 prefix로 후보 조회
        List<ApiKey> candidates = apiKeyRepository.findActiveByPrefix(prefix, Instant.now());
        ApiKey matched = candidates.stream()
                .filter(k -> passwordEncoder.matches(rawKey, k.getKeyHash()))
                .findFirst()
                .orElse(null);

        if (matched == null) {
            sendError(response, HttpStatus.UNAUTHORIZED, "Invalid or expired API key");
            return;
        }

        // Scope 검증: /v1/chat/** → api:chat
        String path = request.getServletPath();
        if (path.startsWith("/v1/chat") && !matched.getScopeList().contains("api:chat")) {
            sendError(response, HttpStatus.UNAUTHORIZED, "Insufficient scope: api:chat required");
            return;
        }

        // Rate Limiting
        String keyId = matched.getId().toString();
        if (!checkRateLimit(response, keyId)) {
            return;
        }

        // SecurityContext 설정
        List<SimpleGrantedAuthority> authorities = matched.getScopeList().stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(matched.getName(), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);

        // last_used_at 비동기 업데이트
        updateLastUsedAt(matched.getId());

        filterChain.doFilter(request, response);
    }

    /**
     * Redis 기반 Rate Limiting.
     * 창별(minute/hour/day) increment + expire 패턴 사용.
     *
     * @return true이면 통과, false이면 429 응답 전송 후 false 반환
     */
    private boolean checkRateLimit(HttpServletResponse response, String keyId) throws IOException {
        long now = Instant.now().getEpochSecond();
        long minuteWindow = now / 60;
        long hourWindow = now / 3600;
        long dayWindow = now / 86400;

        String minuteKey = "rate:" + keyId + ":" + minuteWindow;
        String hourKey = "rate:" + keyId + ":h:" + hourWindow;
        String dayKey = "rate:" + keyId + ":d:" + dayWindow;

        // minute 창
        if (!incrementAndCheck(minuteKey, RATE_MINUTE_TTL, RATE_MINUTE_LIMIT)) {
            sendError(response, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded: 60 requests/minute");
            return false;
        }
        // hour 창
        if (!incrementAndCheck(hourKey, RATE_HOUR_TTL, RATE_HOUR_LIMIT)) {
            sendError(response, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded: 1000 requests/hour");
            return false;
        }
        // day 창
        if (!incrementAndCheck(dayKey, RATE_DAY_TTL, RATE_DAY_LIMIT)) {
            sendError(response, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded: 10000 requests/day");
            return false;
        }
        return true;
    }

    private boolean incrementAndCheck(String redisKey, long ttlSeconds, long limit) {
        Long count = redisTemplate.opsForValue().increment(redisKey);
        if (count == null) return true; // Redis 장애 시 통과 (fail-open)
        if (count == 1L) {
            redisTemplate.expire(redisKey, ttlSeconds, TimeUnit.SECONDS);
        }
        return count <= limit;
    }

    /**
     * last_used_at을 비동기로 업데이트한다.
     * @Async 대신 CompletableFuture.runAsync() 사용 — Filter에 @Async를 붙이면
     * CGLIB 프록시가 OncePerRequestFilter final 메서드와 충돌하므로 직접 비동기 처리.
     */
    private void updateLastUsedAt(UUID apiKeyId) {
        CompletableFuture.runAsync(() ->
            apiKeyRepository.findById(apiKeyId).ifPresent(k -> {
                k.setLastUsedAt(Instant.now());
                apiKeyRepository.save(k);
            })
        );
    }

    private void sendError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, String> body = Map.of(
                "error", status.getReasonPhrase(),
                "message", message
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
