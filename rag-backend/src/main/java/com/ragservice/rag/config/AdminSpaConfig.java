package com.ragservice.rag.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

/**
 * Admin SPA 라우팅.
 * /admin/** → src/main/resources/static/admin/index.html
 *
 * React (CDN, 빌드 없음) BrowserRouter 사용 시 새로고침·직접 URL 진입 처리.
 * ADR-0009: Phase 0 Admin Web UI
 */
@Configuration
public class AdminSpaConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // /admin 에서 /admin/ 로 리디렉션
        registry.addRedirectViewController("/admin", "/admin/");
        // /admin/ 은 index.html 로 포워딩 (React Router 처리)
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");

        // /status → /status/index.html 서빙 (운영 상태 페이지)
        registry.addViewController("/status").setViewName("forward:/status/index.html");
        registry.addViewController("/status/").setViewName("forward:/status/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // /admin/** 정적 자산 → classpath:/static/admin/
        registry.addResourceHandler("/admin/**")
                .addResourceLocations("classpath:/static/admin/")
                .resourceChain(true);

        // /status/** 정적 자산 → classpath:/static/status/
        registry.addResourceHandler("/status/**")
                .addResourceLocations("classpath:/static/status/")
                .resourceChain(true);
    }
}
