package com.ragservice.rag.config;

import com.ragservice.rag.filter.AdminSessionFilter;
import com.ragservice.rag.filter.ApiKeyAuthFilter;
import com.ragservice.rag.filter.TrustedHeaderFilter;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Spring Security 설정.
 *
 * - TrustedHeaderFilter: X-User-* 헤더 외부 주입 차단 (ADR-0006), order=1
 * - ApiKeyAuthFilter: Bearer 토큰 검증, bcrypt, scope, rate limit
 * - BCryptPasswordEncoder: API Key 검증용
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** rag.admin.dev-bypass=true 이면 /api/v1/user/** 에서 X-User-Email 통과 허용. */
    @Value("${rag.admin.dev-bypass:false}")
    private boolean devBypass;

    /**
     * 신뢰 프록시 CIDR 목록 (W-3 security-checklist.md).
     * Open WebUI 파드 등 내부 프록시 IP 대역을 지정한다.
     * 기본값: RFC 1918 전체 (k3s 파드 네트워크 10.42.x.x 포함).
     */
    @Value("${rag.security.trusted-proxy-cidrs:127.0.0.0/8,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}")
    private List<String> trustedProxyCidrs;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * ApiKeyAuthFilter의 서블릿 자동 등록을 비활성화.
     *
     * @Component 때문에 Spring Boot가 서블릿 필터로 자동 등록하면
     * OncePerRequestFilter의 "already-filtered" 마킹으로 인해
     * Spring Security 체인 내부 실행이 건너뛰어진다.
     * 이 경우 SecurityContextHolderFilter가 인증 컨텍스트를 덮어써
     * hasAuthority() 검사가 403으로 실패한다.
     * → SecurityFilterChain 안에서만 실행되도록 setEnabled(false).
     */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyFilterRegistration(ApiKeyAuthFilter filter) {
        FilterRegistrationBean<ApiKeyAuthFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    /**
     * AdminSessionFilter 도 동일하게 서블릿 자동 등록을 비활성화.
     * SecurityFilterChain 안에서만 실행되어야 SecurityContext 가 인가 단계까지 유지된다
     * (자동 등록 시 already-filtered 마킹으로 체인 내부 실행이 건너뛰어짐).
     */
    @Bean
    public FilterRegistrationBean<AdminSessionFilter> adminSessionFilterRegistration(AdminSessionFilter filter) {
        FilterRegistrationBean<AdminSessionFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    /**
     * TrustedHeaderFilter를 Spring Security 앞에서 실행하도록 FilterRegistrationBean으로 등록.
     * order=1로 가장 먼저 실행.
     *
     * M5-3: devBypass 값을 생성자로 전달 — /api/v1/user/** 경로에서 X-User-Email 통과 제어.
     */
    @Bean
    public FilterRegistrationBean<TrustedHeaderFilter> trustedHeaderFilterRegistration() {
        FilterRegistrationBean<TrustedHeaderFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TrustedHeaderFilter(devBypass, trustedProxyCidrs));
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        registration.setName("trustedHeaderFilter");
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ApiKeyAuthFilter apiKeyAuthFilter,
                                           AdminSessionFilter adminSessionFilter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // ── 보안 헤더 ────────────────────────────────────────────────────────────
            // X-Frame-Options: SAMEORIGIN  — Admin SPA iframe 허용 (DENY 는 admin 깨짐)
            // X-Content-Type-Options: nosniff — MIME sniffing 방지
            // Content-Security-Policy — XSS 방지. unsafe-inline 은 Admin SPA 인라인 스크립트 허용
            // Referrer-Policy — 외부 요청 시 referrer 최소 노출
            // Permissions-Policy — 불필요한 브라우저 기능 차단
            // HSTS — HTTPS 강제 (ALB가 TLS 종료 후 내부 HTTP 전달해도 브라우저 레벨 적용)
            .headers(headers -> headers
                .frameOptions(frame -> frame.sameOrigin())
                .contentTypeOptions(Customizer.withDefaults())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31_536_000)  // 1년
                )
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; " +
                    // Admin SPA(/admin/**)가 CDN에서 React·Babel·Tailwind를 로드한다.
                    // 내부 관리 도구이므로 unpkg.com(스크립트)과 cdn.jsdelivr.net(스타일) 허용.
                    "script-src 'self' 'unsafe-inline' https://unpkg.com; " +
                    "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
                    "img-src 'self' data: blob:; " +
                    "font-src 'self' data:; " +
                    "connect-src 'self'; " +
                    "frame-ancestors 'self'; " +
                    "object-src 'none'; " +
                    "base-uri 'self'"
                ))
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                )
                .permissionsPolicy(permissions -> permissions
                    .policy("geolocation=(), microphone=(), camera=(), payment=()")
                )
            )
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
            // AdminSessionFilter 가 ApiKeyAuthFilter 보다 먼저 실행되어야 한다
            // (Open WebUI 세션으로 api:admin 을 먼저 부여 → Bearer 검증 생략).
            .addFilterBefore(adminSessionFilter, ApiKeyAuthFilter.class)
            .authorizeHttpRequests(auth -> auth
                // 컨트롤러가 ResponseStatusException 등을 던지면 Spring 이 /error 로 재디스패치한다.
                // STATELESS 세션이라 ERROR 디스패치에는 인증 컨텍스트가 없어 anyRequest().authenticated()
                // 가 403 으로 막아 실제 상태코드(400 등)를 가린다. ERROR/ASYNC 디스패치는 허용한다.
                // (ERROR 디스패치는 원 요청이 이미 인가를 통과한 뒤에만 발생하므로 안전)
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR,
                        jakarta.servlet.DispatcherType.ASYNC).permitAll()
                .requestMatchers("/api/v1/health", "/api/v1/health/deep", "/actuator/health").permitAll()
                // Admin SPA 정적 셸(/admin/**)은 인가 통과 — 세션 보호는 AdminSessionFilter 가 담당.
                .requestMatchers("/admin/**").permitAll()
                // ADR-0010: incident-response scope — 반드시 admin/** 보다 먼저 와야 함
                .requestMatchers(org.springframework.http.HttpMethod.GET,
                        "/api/v1/admin/audit-logs/*/raw")
                    .hasAuthority("api:incident-response")
                .requestMatchers("/api/v1/admin/**").hasAuthority("api:admin")
                // M5-3: 사용자 파라미터 API — api:chat 또는 api:admin scope 필요
                .requestMatchers("/api/v1/user/**").authenticated()
                .anyRequest().authenticated()
            );
        return http.build();
    }
}
