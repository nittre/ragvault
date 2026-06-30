package com.ragvault.widget.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS 설정 — 두 가지 정책 공존.
 *
 * 1) 위젯 경로 (/v1/widget/**): allowCredentials=false
 *    고객 사이트에서 임베드되므로 쿠키 불필요. X-Site-Key 헤더만 허용.
 *
 * 2) Admin 경로 (/admin/**, /api/v1/auth/**): allowCredentials=true
 *    Admin SPA(dev: localhost:5173)가 httpOnly 쿠키를 주고받아야 함.
 *    allowedOriginPatterns 사용 (allowCredentials=true 시 * 사용 불가).
 */
@Slf4j
@Configuration
public class WebConfig {

    @Value("${widget.cors.allowed-origins:http://localhost:3000}")
    private List<String> widgetAllowedOrigins;

    @Value("${widget.admin.cors.allowed-origins:http://localhost:5173}")
    private List<String> adminAllowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        // 위젯 경로 — credentials 불필요
        CorsConfiguration widgetConfig = new CorsConfiguration();
        widgetConfig.setAllowedOrigins(widgetAllowedOrigins);
        widgetConfig.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        widgetConfig.setAllowedHeaders(List.of("Content-Type", "X-Site-Key"));
        widgetConfig.setAllowCredentials(false);
        widgetConfig.setMaxAge(3600L);
        source.registerCorsConfiguration("/v1/widget/**", widgetConfig);

        // Admin 경로 — httpOnly 쿠키 전달용 credentials=true
        CorsConfiguration adminConfig = new CorsConfiguration();
        adminConfig.setAllowedOriginPatterns(adminAllowedOrigins);
        adminConfig.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        adminConfig.setAllowedHeaders(List.of("Content-Type", "Authorization"));
        adminConfig.setAllowCredentials(true);
        adminConfig.setMaxAge(3600L);
        source.registerCorsConfiguration("/admin/**", adminConfig);
        source.registerCorsConfiguration("/api/v1/auth/**", adminConfig);

        log.info("CORS widget origins: {}", widgetAllowedOrigins);
        log.info("CORS admin origins: {}", adminAllowedOrigins);

        return new CorsFilter((CorsConfigurationSource) source);
    }
}
