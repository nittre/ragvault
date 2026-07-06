package com.ragvault.widget.filter;

import com.ragvault.core.domain.RagRole;
import com.ragvault.core.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * httpOnly 쿠키에서 JWT 를 추출해 SecurityContext 에 인증 정보를 설정한다.
 *
 * 적용 범위: /admin/**, /api/v1/auth/change-password
 * 공개 경로(/api/v1/auth/login, /api/v1/auth/logout, /v1/widget/**)는 shouldNotFilter() 제외.
 *
 * 권한 부여:
 *   - 모든 인증 사용자: api:admin
 *   - SUPER_ADMIN: api:admin + api:super-admin
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // 공개 경로 및 위젯 경로는 필터 제외
        return path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/logout")
                || path.startsWith("/v1/widget/")
                || path.startsWith("/actuator")
                || path.equals("/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String token = extractTokenFromCookie(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtService.parseToken(token);
            String email = claims.getSubject();
            String roleStr = claims.get("role", String.class);

            if (email == null || roleStr == null) {
                chain.doFilter(request, response);
                return;
            }

            RagRole role = RagRole.valueOf(roleStr);
            List<SimpleGrantedAuthority> authorities = buildAuthorities(role);

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(email, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);

        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 검증 실패: {}", e.getMessage());
        }

        chain.doFilter(request, response);
    }

    private List<SimpleGrantedAuthority> buildAuthorities(RagRole role) {
        List<SimpleGrantedAuthority> list = new ArrayList<>();
        list.add(new SimpleGrantedAuthority("api:admin"));
        if (role == RagRole.SUPER_ADMIN) {
            list.add(new SimpleGrantedAuthority("api:super-admin"));
        }
        return list;
    }

    private String extractTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if ("widget-token".equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
