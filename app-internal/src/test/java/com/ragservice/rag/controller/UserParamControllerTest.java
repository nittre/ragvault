package com.ragservice.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragservice.rag.config.SecurityConfig;
import com.ragservice.rag.domain.AdminParamLimit;
import com.ragservice.rag.domain.UserParamProfile;
import com.ragservice.rag.filter.ApiKeyAuthFilter;
import com.ragservice.rag.filter.JwtAuthFilter;
import com.ragservice.rag.repository.AdminParamLimitRepository;
import com.ragservice.rag.repository.ConversationParamOverrideRepository;
import com.ragservice.rag.repository.UserParamProfileRepository;
import com.ragservice.rag.service.ParameterCacheService;
import com.ragservice.rag.service.ParameterValidator;
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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * UserParamController 슬라이스 테스트.
 *
 * M5-3: GET/PUT/DELETE /api/v1/user/param-profile 동작 검증.
 * ADR-0005: Guard B 파라미터 변경 시 400 반환 검증.
 *
 * @WithMockUser 로 SecurityContext 에 인증 컨텍스트 주입.
 * ApiKeyAuthFilter / AdminSessionFilter 는 call-through mock — 실제 필터 로직 스킵.
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
    private UserParamProfileRepository userParamProfileRepository;

    @MockitoBean
    private AdminParamLimitRepository adminParamLimitRepository;

    @MockitoBean
    private ConversationParamOverrideRepository conversationParamOverrideRepository;

    @MockitoBean
    private ParameterValidator parameterValidator;

    @MockitoBean
    private ParameterCacheService parameterCacheService;

    @BeforeEach
    void setUp() throws Exception {
        // ApiKeyAuthFilter / AdminSessionFilter call-through
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
        // 기본: 검증 통과
        when(parameterValidator.validate(any())).thenReturn(ParameterValidator.ValidationResult.pass());
    }

    // -------------------------------------------------------------------------
    // 1. GET — 프로필 없는 사용자: 빈 params 반환
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("GET: 프로필 없으면 params=빈맵, defaults·limits 포함 응답")
    @WithMockUser(username = "user@example.com", authorities = "api:chat")
    void getProfile_no_profile_returns_empty_params() throws Exception {
        when(userParamProfileRepository.findByUserEmail(anyString()))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/user/param-profile")
                        .header("X-User-Email", "user@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.params").isMap())
                .andExpect(jsonPath("$.defaults").isMap())
                .andExpect(jsonPath("$.defaults.top_k").value(5))
                .andExpect(jsonPath("$.limits").isMap());
    }

    // -------------------------------------------------------------------------
    // 2. GET — 프로필 있는 사용자: 저장된 params 반환
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("GET: 프로필 있으면 저장된 params 반환")
    @WithMockUser(username = "user@example.com", authorities = "api:chat")
    void getProfile_with_profile_returns_saved_params() throws Exception {
        UserParamProfile profile = new UserParamProfile();
        profile.setUserEmail("user@example.com");
        profile.setParams(new HashMap<>(Map.of("top_k", 8, "temperature", 0.5)));
        when(userParamProfileRepository.findByUserEmail("user@example.com"))
                .thenReturn(Optional.of(profile));

        mockMvc.perform(get("/api/v1/user/param-profile")
                        .header("X-User-Email", "user@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.params.top_k").value(8))
                .andExpect(jsonPath("$.params.temperature").value(0.5));
    }

    // -------------------------------------------------------------------------
    // 3. GET — limits 에 Guard A/B 정보 포함
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("GET: limits 에 Guard B 잠금 정보 포함")
    @WithMockUser(username = "user@example.com", authorities = "api:chat")
    void getProfile_includes_guard_b_limits() throws Exception {
        when(userParamProfileRepository.findByUserEmail(anyString()))
                .thenReturn(Optional.empty());

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
    // 4. PUT — 정상 저장
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("PUT: 유효한 파라미터 저장 → 200 + 갱신된 프로필 반환")
    @WithMockUser(username = "user@example.com", authorities = "api:chat")
    void updateProfile_valid_params_saves_and_returns() throws Exception {
        UserParamProfile saved = new UserParamProfile();
        saved.setUserEmail("user@example.com");
        saved.setParams(new HashMap<>(Map.of("top_k", 10)));
        when(userParamProfileRepository.findByUserEmail("user@example.com"))
                .thenReturn(Optional.of(saved));
        when(userParamProfileRepository.save(any())).thenReturn(saved);

        Map<String, Object> body = Map.of("top_k", 10);

        mockMvc.perform(put("/api/v1/user/param-profile")
                        .header("X-User-Email", "user@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.params.top_k").value(10));

        verify(parameterCacheService).evictByUser("user@example.com");
    }

    // -------------------------------------------------------------------------
    // 5. PUT — 인증 없으면 401
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("PUT: 인증 없으면 403 (Spring Security — 미인증 접근 거부)")
    void updateProfile_no_auth_returns_403() throws Exception {
        Map<String, Object> body = Map.of("top_k", 10);

        mockMvc.perform(put("/api/v1/user/param-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // 6. PUT — Guard B 파라미터 변경 시도 → 400
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("PUT: Guard B 파라미터(sql_temperature) 변경 시도 → 400")
    @WithMockUser(username = "user@example.com", authorities = "api:chat")
    void updateProfile_guard_b_param_returns_400() throws Exception {
        when(parameterValidator.validate(any()))
                .thenReturn(ParameterValidator.ValidationResult.fail(
                        "sql_temperature 는 관리자 정책으로 고정되어 있어 변경할 수 없습니다."));

        Map<String, Object> body = Map.of("sql_temperature", 0.9);

        mockMvc.perform(put("/api/v1/user/param-profile")
                        .header("X-User-Email", "user@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // 7. DELETE — 프로필 삭제 + 캐시 무효화
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("DELETE: 프로필 삭제 → 204, evictByUser 호출")
    @WithMockUser(username = "user@example.com", authorities = "api:chat")
    void deleteProfile_returns_204_and_evicts_cache() throws Exception {
        mockMvc.perform(delete("/api/v1/user/param-profile")
                        .header("X-User-Email", "user@example.com"))
                .andExpect(status().isNoContent());

        verify(userParamProfileRepository).deleteByUserEmail("user@example.com");
        verify(parameterCacheService).evictByUser("user@example.com");
    }

    // -------------------------------------------------------------------------
    // 8. DELETE /all — 프로필 + 대화 override 전체 삭제
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("DELETE /all: 프로필 + 모든 대화 override 삭제 → 204")
    @WithMockUser(username = "user@example.com", authorities = "api:chat")
    void deleteAll_removes_profile_and_overrides() throws Exception {
        mockMvc.perform(delete("/api/v1/user/param-profile/all")
                        .header("X-User-Email", "user@example.com"))
                .andExpect(status().isNoContent());

        verify(userParamProfileRepository).deleteByUserEmail("user@example.com");
        verify(conversationParamOverrideRepository).deleteByUserEmail("user@example.com");
        verify(parameterCacheService).evictByUser("user@example.com");
    }
}
