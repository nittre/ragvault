package com.ragservice.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragservice.rag.config.SecurityConfig;
import com.ragservice.rag.domain.AdminParamLimit;
import com.ragservice.rag.filter.ApiKeyAuthFilter;
import com.ragservice.rag.filter.JwtAuthFilter;
import com.ragservice.rag.repository.AdminParamLimitRepository;
import com.ragservice.rag.service.AdminDefaultsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UserParamController 슬라이스 테스트.
 *
 * GET /api/v1/user/param-profile 동작 검증 (전역 기본값 + Guard A/B 한도 조회).
 * 사용자 설정은 세션(브라우저) 한정으로만 관리되고 서버에 영구 저장하지 않으므로 이 컨트롤러는 조회 전용이다.
 *
 * @WithMockUser 로 SecurityContext 에 인증 컨텍스트 주입.
 * ApiKeyAuthFilter / JwtAuthFilter 는 call-through mock — 실제 필터 로직 스킵.
 */
@WebMvcTest(UserParamController.class)
@Import(SecurityConfig.class)
class UserParamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ApiKeyAuthFilter apiKeyAuthFilter;

    @MockitoBean
    private JwtAuthFilter jwtAuthFilter;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private AdminParamLimitRepository adminParamLimitRepository;

    @MockitoBean
    private AdminDefaultsService adminDefaultsService;

    @BeforeEach
    void setUp() throws Exception {
        // ApiKeyAuthFilter / JwtAuthFilter call-through
        doAnswer(invocation -> {
            ServletRequest req = invocation.getArgument(0);
            ServletResponse res = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(apiKeyAuthFilter).doFilter(any(), any(), any());

        doAnswer(invocation -> {
            ServletRequest req = invocation.getArgument(0);
            ServletResponse res = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());

        // 기본: 어드민 한계 없음
        when(adminParamLimitRepository.findAll()).thenReturn(List.of());
        // 기본: Stage 1 기본값 (ADR-0005: admin_param_limits.default_value 기반, 서버 하드코딩 없음)
        when(adminDefaultsService.resolveDefaults()).thenReturn(Map.of("top_k", 5));
    }

    // -------------------------------------------------------------------------
    // 1. GET — 전역 기본값 + limits 반환
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("GET: defaults·limits 포함 응답")
    @WithMockUser(username = "user@example.com", authorities = "api:chat")
    void getProfile_returns_defaults_and_limits() throws Exception {
        mockMvc.perform(get("/api/v1/user/param-profile")
                        .header("X-User-Email", "user@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaults").isMap())
                .andExpect(jsonPath("$.defaults.top_k").value(5))
                .andExpect(jsonPath("$.limits").isMap());
    }

    // -------------------------------------------------------------------------
    // 2. GET — limits 에 Guard A/B 정보 포함
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("GET: limits 에 Guard B 잠금 정보 포함")
    @WithMockUser(username = "user@example.com", authorities = "api:chat")
    void getProfile_includes_guard_b_limits() throws Exception {
        AdminParamLimit guardB = AdminParamLimit.builder()
                .paramName("sql_temperature")
                .fixedValue(new BigDecimal("0.1"))
                .guardType("B")
                .lockedReason("SQL 정확도 유지")
                .build();
        when(adminParamLimitRepository.findAll()).thenReturn(List.of(guardB));

        mockMvc.perform(get("/api/v1/user/param-profile")
                        .header("X-User-Email", "user@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limits.sql_temperature.locked").value(true))
                .andExpect(jsonPath("$.limits.sql_temperature.lockedReason").value("SQL 정확도 유지"));
    }

    // -------------------------------------------------------------------------
    // 3. GET — 인증 없으면 403
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("GET: 인증 없으면 403 (Spring Security — 미인증 접근 거부)")
    void getProfile_no_auth_returns_403() throws Exception {
        mockMvc.perform(get("/api/v1/user/param-profile"))
                .andExpect(status().isForbidden());
    }
}
