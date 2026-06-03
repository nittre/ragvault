package com.ragservice.rag.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Admin Web UI 세션 검증 필터.
 *
 * ADR-0009 N2 결정:
 * 1. 요청에서 Open WebUI 세션 쿠키(token) 추출
 * 2. Open WebUI /auth/verify 호출 (k3s 내부 통신)
 * 3. is_admin 확인
 * 4. 검증 결과를 Redis 에 60초 캐시 (매 요청마다 외부 호출 방지)
 * 5. 검증 성공 시 SecurityContext 에 api:admin 권한 부여
 *    → /api/v1/admin/** 도 Open WebUI 관리자 세션으로 인증된다 (Bearer 키 불필요).
 *
 * 적용 경로:
 *   - /admin/**          (SPA 라우트) — 세션 없으면 거부
 *   - /api/v1/admin/**   (Admin API) — 세션 있으면 api:admin 부여, 없으면 통과 후
 *                          ApiKeyAuthFilter 의 Bearer 검증으로 폴백 (curl/프로그램 호출용)
 *
 * 이 필터는 SecurityFilterChain 안에서 ApiKeyAuthFilter 보다 먼저 실행된다 (SecurityConfig).
 */
@Slf4j
@Component
public class AdminSessionFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;
    private final RestTemplate restTemplate;

    @Value("${rag.admin.openwebui-verify-url:http://open-webui:8080/auth/verify}")
    private String openwebuiVerifyUrl;

    /** dev 환경 한정 우회 — X-Dev-Admin: true 헤더로 admin 권한 부여. 운영 프로파일은 false. */
    @Value("${rag.admin.dev-bypass:false}")
    private boolean devBypass;

    private static final String CACHE_PREFIX = "admin_session:";
    private static final long CACHE_TTL_SECONDS = 60;
    private static final String ADMIN_AUTHORITY = "api:admin";

    public AdminSessionFilter(StringRedisTemplate redis) {
        this.redis = redis;
        this.restTemplate = new RestTemplate();
    }

    private boolean isApiPath(String path) {
        return path.startsWith("/api/v1/admin");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // Admin SPA(/admin/**) 또는 Admin API(/api/v1/admin/**) 만 처리.
        if (!path.startsWith("/admin") && !isApiPath(path)) {
            return true;
        }
        // 정적 자산 / SPA 셸은 인증 없이 서빙 (데이터는 API 경로에서 보호).
        return path.startsWith("/admin/assets/") ||
               path.equals("/admin/favicon.ico") ||
               path.equals("/admin/index.html") ||
               path.equals("/admin") ||
               path.equals("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String path = request.getServletPath();

        // dev 우회: 운영 프로파일에서는 devBypass=false 이므로 무력화됨.
        if (devBypass && "true".equals(request.getHeader("X-Dev-Admin"))) {
            authenticateAsAdmin();
            chain.doFilter(request, response);
            return;
        }

        String sessionCookie = extractSessionCookie(request);
        if (sessionCookie == null) {
            // API 경로는 Bearer 폴백을 허용 (ApiKeyAuthFilter 가 이후 검증).
            if (isApiPath(path)) { chain.doFilter(request, response); return; }
            sendUnauthorized(response, "세션 쿠키가 없습니다.");
            return;
        }

        String cacheKey = CACHE_PREFIX + sessionCookie.hashCode();
        String cached = redis.opsForValue().get(cacheKey);

        if ("admin".equals(cached)) {
            authenticateAsAdmin();
            chain.doFilter(request, response);
            return;
        }
        if ("denied".equals(cached)) {
            denyOrFallback(request, response, chain, path);
            return;
        }

        // Open WebUI /auth/verify 호출
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Cookie", "token=" + sessionCookie);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    openwebuiVerifyUrl, HttpMethod.GET, entity, Map.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                Boolean isAdmin = (Boolean) resp.getBody().get("is_admin");
                if (Boolean.TRUE.equals(isAdmin)) {
                    redis.opsForValue().set(cacheKey, "admin", CACHE_TTL_SECONDS, TimeUnit.SECONDS);
                    authenticateAsAdmin();
                    chain.doFilter(request, response);
                    return;
                }
            }
            redis.opsForValue().set(cacheKey, "denied", CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            denyOrFallback(request, response, chain, path);

        } catch (Exception e) {
            log.error("Open WebUI /auth/verify 호출 실패: {}", e.getMessage());
            // 개발 환경 fallback: X-Dev-Admin 헤더 허용 (devBypass 시)
            if (devBypass && "true".equals(request.getHeader("X-Dev-Admin"))) {
                authenticateAsAdmin();
                chain.doFilter(request, response);
                return;
            }
            if (isApiPath(path)) { chain.doFilter(request, response); return; }
            sendUnauthorized(response, "세션 검증 서비스에 연결할 수 없습니다.");
        }
    }

    /** 세션이 admin 이 아닐 때: SPA 경로는 거부, API 경로는 Bearer 폴백 허용. */
    private void denyOrFallback(HttpServletRequest request, HttpServletResponse response,
                                FilterChain chain, String path) throws IOException, ServletException {
        if (isApiPath(path)) { chain.doFilter(request, response); return; }
        sendForbidden(response, "admin 권한이 필요합니다.");
    }

    private void authenticateAsAdmin() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "openwebui-admin", null, List.of(new SimpleGrantedAuthority(ADMIN_AUTHORITY)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private String extractSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if ("token".equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
