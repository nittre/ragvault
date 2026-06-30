package com.ragvault.widget.config;

import com.ragvault.widget.filter.JwtAuthFilter;
import com.ragvault.widget.filter.SiteKeyFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 설정.
 *
 * 두 인증 체계 공존:
 *   1) 익명 방문자 → SiteKeyFilter (X-Site-Key 헤더) — /v1/widget/** 전용
 *      SiteKeyFilter 는 @Component 이므로 Spring 이 자동 등록하려 한다.
 *      FilterRegistrationBean 으로 자동 등록 비활성화 → SecurityFilterChain 에서만 동작.
 *      (단, SiteKeyFilter 는 shouldNotFilter() 로 /v1/widget/** 외 경로 자체 제외하므로
 *       Security 체인에 추가하지 않아도 되지만, 자동 등록을 막아 중복 적용을 방지한다.)
 *
 *   2) 운영자 → JwtAuthFilter (httpOnly 쿠키 JWT) — /admin/**, /api/v1/auth/**
 *      JwtAuthFilter 도 @Component 이므로 동일하게 자동 등록 비활성화.
 *
 * 경로 정책:
 *   - /api/v1/auth/login, /api/v1/auth/logout, /actuator/health → permitAll
 *   - /v1/widget/**  → permitAll (site-key 검증은 SiteKeyFilter 가 담당)
 *   - /admin/**      → authenticated (JwtAuthFilter 통과 필수)
 *   - /admin/users POST/PUT/DELETE → api:super-admin
 *   - 그 외 → authenticated
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 미인증 요청 → 401 JSON 응답 (Spring Security 기본은 403 또는 redirect).
     */
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Unauthorized\"}");
        };
    }

    /**
     * SiteKeyFilter 자동 서블릿 등록 비활성화.
     * SecurityFilterChain 외부에서 이중 실행되지 않도록.
     */
    @Bean
    public FilterRegistrationBean<SiteKeyFilter> siteKeyFilterRegistration(SiteKeyFilter filter) {
        FilterRegistrationBean<SiteKeyFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    /**
     * JwtAuthFilter 자동 서블릿 등록 비활성화.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtFilterRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthFilter jwtAuthFilter,
                                           SiteKeyFilter siteKeyFilter,
                                           AuthenticationEntryPoint authenticationEntryPoint) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint))
            // JwtAuthFilter → SiteKeyFilter 순서로 체인에 삽입
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(siteKeyFilter, JwtAuthFilter.class)
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(
                        jakarta.servlet.DispatcherType.ERROR,
                        jakarta.servlet.DispatcherType.ASYNC).permitAll()
                // 공개 경로
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                // 위젯 경로 — Security 레벨에서 허용, site-key 는 SiteKeyFilter 담당
                .requestMatchers("/v1/widget/**").permitAll()
                // 사용자 관리 쓰기 — super-admin 전용
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                        "/api/admin/users").hasAuthority("api:super-admin")
                .requestMatchers(org.springframework.http.HttpMethod.PUT,
                        "/api/admin/users/**").hasAuthority("api:super-admin")
                .requestMatchers(org.springframework.http.HttpMethod.DELETE,
                        "/api/admin/users/**").hasAuthority("api:super-admin")
                // admin 경로 전체 — JWT 인증 필수
                .requestMatchers("/api/admin/**").authenticated()
                // 비밀번호 변경 — JWT 인증 필수
                .requestMatchers("/api/v1/auth/change-password").authenticated()
                .anyRequest().authenticated()
            );
        return http.build();
    }
}
