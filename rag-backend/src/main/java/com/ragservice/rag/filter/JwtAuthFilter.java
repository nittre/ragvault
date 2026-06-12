package com.ragservice.rag.filter;

import com.ragservice.rag.domain.RagRole;
import com.ragservice.rag.service.JwtService;
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
 * ADR-0011: httpOnly 쿠키에서 JWT 를 추출해 SecurityContext 에 인증 정보를 설정한다.
 *
 * 실행 순서: JwtAuthFilter → ApiKeyAuthFilter
 * JWT 검증 성공 시 ApiKeyAuthFilter.shouldNotFilter() 가 skip 한다.
 *
 * 권한 부여:
 *   - 모든 인증 사용자: api:chat
 *   - ADMIN 이상: api:admin
 *   - SUPER_ADMIN: api:admin + api:super-admin
 *
 * 적용 범위: 모든 경로 (/api/v1/auth/** 는 shouldNotFilter() 제외).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // 인증 불필요 공개 경로만 제외. /api/v1/auth/change-password 는 JWT 필요.
        return path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/logout")
                || path.startsWith("/api/v1/health")
                || path.startsWith("/actuator");
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
        list.add(new SimpleGrantedAuthority("api:chat"));
        if (role == RagRole.ADMIN || role == RagRole.SUPER_ADMIN) {
            list.add(new SimpleGrantedAuthority("api:admin"));
        }
        if (role == RagRole.SUPER_ADMIN) {
            list.add(new SimpleGrantedAuthority("api:super-admin"));
        }
        return list;
    }

    private String extractTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if ("token".equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
