package com.ragvault.widget.filter;

import com.ragvault.widget.service.SiteKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Site-key 검증 필터.
 *
 * X-Site-Key 헤더 값을 DB(site_keys 테이블) 기반으로 검증.
 * 활성 키가 없으면 401 반환.
 * /v1/widget/** 경로에만 적용. CORS preflight(OPTIONS) 는 제외.
 *
 * DB 조회 결과는 CacheConfig "siteKeys" 캐시로 보호.
 * 키 수정/비활성화/삭제 시 SiteKeyService.evictKeyCache() 로 무효화.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiteKeyFilter extends OncePerRequestFilter {

    private static final String SITE_KEY_HEADER = "X-Site-Key";

    private final SiteKeyService siteKeyService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // CORS preflight(OPTIONS)는 커스텀 헤더 없이 오므로 통과시킨다 — CorsFilter 가 처리.
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String path = request.getRequestURI();
        // /v1/widget/** 경로에만 적용
        return !path.startsWith("/v1/widget/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String siteKey = request.getHeader(SITE_KEY_HEADER);

        if (siteKey == null || siteKey.isBlank()) {
            log.warn("Missing X-Site-Key header from {}", request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Missing X-Site-Key header\"}");
            return;
        }

        if (!siteKeyService.isValidKey(siteKey)) {
            log.warn("Invalid site key '{}' from {}", siteKey, request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Invalid site key\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
