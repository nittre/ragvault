package com.ragservice.rag.config;

import com.ragservice.rag.filter.ApiKeyAuthFilter;
import com.ragservice.rag.filter.JwtAuthFilter;
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
 * ADR-0011: AdminSessionFilter(Open WebUI 세션) → JwtAuthFilter(자체 JWT) 교체.
 * - TrustedHeaderFilter: X-User-* 헤더 외부 주입 차단 (ADR-0006), order=1
 * - JwtAuthFilter: httpOnly 쿠키 JWT 검증, SecurityContext 설정
 * - ApiKeyAuthFilter: Bearer 토큰 검증 (JwtAuthFilter 인증 후 skip)
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${rag.admin.dev-bypass:false}")
    private boolean devBypass;

    @Value("${rag.security.trusted-proxy-cidrs:127.0.0.0/8,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}")
    private List<String> trustedProxyCidrs;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyFilterRegistration(ApiKeyAuthFilter filter) {
        FilterRegistrationBean<ApiKeyAuthFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtFilterRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

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
                                           JwtAuthFilter jwtAuthFilter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers
                .frameOptions(frame -> frame.sameOrigin())
                .contentTypeOptions(Customizer.withDefaults())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31_536_000)
                )
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; " +
                    "script-src 'self' 'unsafe-inline'; " +
                    "style-src 'self' 'unsafe-inline'; " +
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
            // JwtAuthFilter 먼저 실행, ApiKeyAuthFilter 는 그 뒤
            // addFilterBefore(A, B) = A를 B 바로 앞에 삽입
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, ApiKeyAuthFilter.class)
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR,
                        jakarta.servlet.DispatcherType.ASYNC).permitAll()
                .requestMatchers("/api/v1/health", "/api/v1/health/deep", "/actuator/health").permitAll()
                // ADR-0011: 로그인·로그아웃만 공개. change-password 는 JWT 인증 필요.
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
                // 신규 React SPA 정적 셸 — index.html 과 빌드 asset 서빙
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                // ADR-0010: incident-response scope
                .requestMatchers(org.springframework.http.HttpMethod.GET,
                        "/api/v1/admin/audit-logs/*/raw")
                    .hasAuthority("api:incident-response")
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                        "/api/v1/admin/users").hasAuthority("api:super-admin")
                .requestMatchers(org.springframework.http.HttpMethod.PUT,
                        "/api/v1/admin/users/**").hasAuthority("api:super-admin")
                .requestMatchers(org.springframework.http.HttpMethod.DELETE,
                        "/api/v1/admin/users/**").hasAuthority("api:super-admin")
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                        "/api/v1/admin/users/*/reset-password").hasAuthority("api:super-admin")
                .requestMatchers("/api/v1/admin/**").hasAuthority("api:admin")
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/search")
                    .hasAuthority("api:chat")
                .requestMatchers("/api/v1/user/**").authenticated()
                .anyRequest().authenticated()
            );
        return http.build();
    }
}
