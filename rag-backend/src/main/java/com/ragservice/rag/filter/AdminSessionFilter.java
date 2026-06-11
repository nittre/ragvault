package com.ragservice.rag.filter;

import com.ragservice.rag.domain.RagRole;
import com.ragservice.rag.domain.RagUser;
import com.ragservice.rag.service.RagUserService;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Admin Web UI 세션 검증 필터.
 *
 * ADR-0009 N2 결정:
 * 1. 요청에서 Open WebUI 세션 쿠키(token) 추출
 * 2. Open WebUI /auth/verify 호출 (k3s 내부 통신)
 * 3. email 추출 → RagUserService.findByEmail() 로 역할 조회
 * 4. 검증 결과를 Redis 에 60초 캐시 (매 요청마다 외부 호출 방지)
 *    캐시 값: "SUPER_ADMIN" / "ADMIN" / "USER" / "NOT_FOUND"
 * 5. 역할에 따라 SecurityContext 에 권한 부여:
 *    - SUPER_ADMIN → [api:admin, api:super-admin]
 *    - ADMIN       → [api:admin]
 *    - USER / 없음 / inactive → 403 ACCESS_DENIED
 *
 * 적용 경로:
 *   - /admin/**        (SPA 라우트) — 세션 없으면 거부
 *   - /api/v1/admin/** (Admin API) — 세션 있으면 권한 부여, 없으면 ApiKeyAuthFilter 폴백
 */
@Slf4j
@Component
public class AdminSessionFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;
    private final RagUserService ragUserService;
    private final RestTemplate restTemplate;

    @Value("${rag.admin.openwebui-verify-url:http://open-webui:8080/auth/verify}")
    private String openwebuiVerifyUrl;

    /** dev 환경 우회 — true 이면 api:admin + api:super-admin 을 무조건 부여. */
    @Value("${rag.admin.dev-bypass:false}")
    private boolean devBypass;

    private static final String CACHE_PREFIX = "admin_session:";
    private static final long CACHE_TTL_SECONDS = 60;

    private static final String CACHE_SUPER_ADMIN = "SUPER_ADMIN";
    private static final String CACHE_ADMIN       = "ADMIN";
    private static final String CACHE_USER        = "USER";
    private static final String CACHE_NOT_FOUND   = "NOT_FOUND";

    public AdminSessionFilter(StringRedisTemplate redis, RagUserService ragUserService) {
        this.redis = redis;
        this.ragUserService = ragUserService;
        this.restTemplate = new RestTemplate();
    }

    private boolean isApiPath(String path) {
        return path.startsWith("/api/v1/admin");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (!path.startsWith("/admin") && !isApiPath(path)) {
            return true;
        }
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

        // dev 우회: devBypass=true(internal 프로파일)이면 api:admin + api:super-admin 무조건 부여.
        if (devBypass) {
            authenticateWithAuthorities("dev-bypass", List.of("api:admin", "api:super-admin"));
            chain.doFilter(request, response);
            return;
        }

        String sessionCookie = extractSessionCookie(request);
        if (sessionCookie == null) {
            if (isApiPath(path)) { chain.doFilter(request, response); return; }
            sendUnauthorized(response, "세션 쿠키가 없습니다.");
            return;
        }

        String cacheKey = CACHE_PREFIX + sessionCookie.hashCode();
        String cached = redis.opsForValue().get(cacheKey);

        if (cached != null) {
            handleCachedRole(cached, request, response, chain, path);
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
                String email = (String) resp.getBody().get("email");

                if (email != null && !email.isBlank()) {
                    Optional<RagUser> userOpt = ragUserService.findByEmail(email);

                    if (userOpt.isPresent()) {
                        RagUser user = userOpt.get();
                        String roleStr = user.getRole().name();
                        redis.opsForValue().set(cacheKey, roleStr, CACHE_TTL_SECONDS, TimeUnit.SECONDS);

                        if (user.getRole() == RagRole.SUPER_ADMIN) {
                            authenticateWithAuthorities(email, List.of("api:admin", "api:super-admin"));
                            chain.doFilter(request, response);
                        } else if (user.getRole() == RagRole.ADMIN) {
                            authenticateWithAuthorities(email, List.of("api:admin"));
                            chain.doFilter(request, response);
                        } else {
                            // USER 역할
                            redis.opsForValue().set(cacheKey, CACHE_USER, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
                            sendAccessDenied(response);
                        }
                        return;
                    } else {
                        // rag_users 에 없는 사용자
                        redis.opsForValue().set(cacheKey, CACHE_NOT_FOUND, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
                        denyOrFallback(request, response, chain, path);
                        return;
                    }
                }
            }

            // /auth/verify 응답이 비정상
            redis.opsForValue().set(cacheKey, CACHE_NOT_FOUND, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            denyOrFallback(request, response, chain, path);

        } catch (Exception e) {
            log.error("Open WebUI /auth/verify 호출 실패: {}", e.getMessage());
            if (isApiPath(path)) { chain.doFilter(request, response); return; }
            sendUnauthorized(response, "세션 검증 서비스에 연결할 수 없습니다.");
        }
    }

    /** 캐시된 역할에 따라 처리 */
    private void handleCachedRole(String cached, HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain, String path) throws IOException, ServletException {
        switch (cached) {
            case CACHE_SUPER_ADMIN -> {
                // 캐시에는 email 이 없으므로 principal 은 "cached-session" 으로 설정
                authenticateWithAuthorities("cached-session", List.of("api:admin", "api:super-admin"));
                chain.doFilter(request, response);
            }
            case CACHE_ADMIN -> {
                authenticateWithAuthorities("cached-session", List.of("api:admin"));
                chain.doFilter(request, response);
            }
            case CACHE_USER, CACHE_NOT_FOUND -> denyOrFallback(request, response, chain, path);
            default -> denyOrFallback(request, response, chain, path);
        }
    }

    /** 역할 없음/USER: SPA 경로 거부, API 경로는 Bearer 폴백 허용 */
    private void denyOrFallback(HttpServletRequest request, HttpServletResponse response,
                                FilterChain chain, String path) throws IOException, ServletException {
        if (isApiPath(path)) { chain.doFilter(request, response); return; }
        sendAccessDenied(response);
    }

    private void authenticateWithAuthorities(String principal, List<String> authorityNames) {
        List<SimpleGrantedAuthority> authorities = authorityNames.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
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
        response.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}");
    }

    private void sendAccessDenied(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"ACCESS_DENIED\",\"message\":\"접근 권한이 없습니다.\"}");
    }
}
