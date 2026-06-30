package com.ragservice.rag.service;

import com.ragservice.rag.domain.AdminParamLimit;
import com.ragservice.rag.domain.ConversationParamOverride;
import com.ragservice.rag.domain.UserParamProfile;
import com.ragservice.rag.dto.EffectiveParams;
import com.ragservice.rag.repository.AdminParamLimitRepository;
import com.ragservice.rag.repository.ConversationParamOverrideRepository;
import com.ragservice.rag.repository.UserParamProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ParameterResolver 단위 테스트.
 *
 * ADR-0005 7단계 우선순위 체인 + Guard A/B 검증.
 * 최소 12개 케이스.
 */
@ExtendWith(MockitoExtension.class)
class ParameterResolverTest {

    @Mock
    private SearchConfigMappingService searchConfigMappingService;
    @Mock
    private UserParamProfileRepository userParamProfileRepository;
    @Mock
    private ConversationParamOverrideRepository conversationParamOverrideRepository;
    @Mock
    private AdminParamLimitRepository adminParamLimitRepository;
    @Mock
    private ParameterCacheService parameterCacheService;

    private ParameterResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ParameterResolver(
                searchConfigMappingService,
                userParamProfileRepository,
                conversationParamOverrideRepository,
                adminParamLimitRepository,
                parameterCacheService
        );
        // 기본: 캐시 미스
        lenient().when(parameterCacheService.get(any(), any())).thenReturn(Optional.empty());
        // 기본: admin_param_limits 비어있음
        lenient().when(adminParamLimitRepository.findAll()).thenReturn(List.of());
        // 기본: search_config 비어있음
        lenient().when(searchConfigMappingService.getParams()).thenReturn(Map.of());
        // 기본: 사용자 프로필 없음
        lenient().when(userParamProfileRepository.findByUserEmail(any())).thenReturn(Optional.empty());
        // 기본: 대화 override 없음
        lenient().when(conversationParamOverrideRepository
                .findByConversationIdAndUserEmail(any(), any())).thenReturn(Optional.empty());
    }

    // -------------------------------------------------------------------------
    // 1. stage1_only: 모든 Stage 비어있으면 HardcodedDefaults 값 반환
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("stage1_only: 모든 Stage 비어있으면 HardcodedDefaults 기본값 반환")
    void stage1_only() {
        EffectiveParams result = resolver.resolve(null, null, null);

        assertThat(result.values()).containsEntry("top_k", 5);
        assertThat(result.values()).containsEntry("similarity_threshold", 0.65);
        assertThat(result.values()).containsEntry("temperature", 0.7);
        assertThat(result.values()).containsEntry("force_path", "AUTO");
        assertThat(result.sources()).allSatisfy(
                (k, v) -> assertThat(v).isEqualTo("stage1_hardcoded"));
    }

    // -------------------------------------------------------------------------
    // 2. stage2_overrides_stage1: search_config 값이 기본값 덮어씀
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("stage2_overrides_stage1: search_config top_k=10 이 hardcoded top_k=5 를 덮어씀")
    void stage2_overrides_stage1() {
        when(searchConfigMappingService.getParams()).thenReturn(Map.of("top_k", 10));

        EffectiveParams result = resolver.resolve(null, null, null);

        assertThat(result.values()).containsEntry("top_k", 10);
        assertThat(result.sources()).containsEntry("top_k", "stage2_search_config");
    }

    // -------------------------------------------------------------------------
    // 3. stage4_overrides_stage2: user profile이 search_config 덮어씀
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("stage4_overrides_stage2: 사용자 프로필 top_k=7 이 search_config top_k=10 을 덮어씀")
    void stage4_overrides_stage2() {
        when(searchConfigMappingService.getParams()).thenReturn(Map.of("top_k", 10));

        UserParamProfile profile = new UserParamProfile();
        profile.setUserEmail("user@test.com");
        profile.setParams(new HashMap<>(Map.of("top_k", 7)));
        when(userParamProfileRepository.findByUserEmail("user@test.com"))
                .thenReturn(Optional.of(profile));

        EffectiveParams result = resolver.resolve("user@test.com", null, null);

        assertThat(result.values()).containsEntry("top_k", 7);
        assertThat(result.sources()).containsEntry("top_k", "stage4_user_profile");
    }

    // -------------------------------------------------------------------------
    // 4. stage5_overrides_stage4: conversation override가 profile 덮어씀
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("stage5_overrides_stage4: 대화별 temperature=0.3 이 프로필 temperature=0.6 을 덮어씀")
    void stage5_overrides_stage4() {
        UserParamProfile profile = new UserParamProfile();
        profile.setUserEmail("user@test.com");
        profile.setParams(new HashMap<>(Map.of("temperature", 0.6)));
        when(userParamProfileRepository.findByUserEmail("user@test.com"))
                .thenReturn(Optional.of(profile));

        ConversationParamOverride override = new ConversationParamOverride();
        override.setConversationId("conv-123");
        override.setUserEmail("user@test.com");
        override.setParams(new HashMap<>(Map.of("temperature", 0.3)));
        when(conversationParamOverrideRepository
                .findByConversationIdAndUserEmail("conv-123", "user@test.com"))
                .thenReturn(Optional.of(override));

        EffectiveParams result = resolver.resolve("user@test.com", "conv-123", null);

        assertThat(result.values()).containsEntry("temperature", 0.3);
        assertThat(result.sources()).containsEntry("temperature", "stage5_conversation_override");
    }

    // -------------------------------------------------------------------------
    // 5. stage6_overrides_stage5: request ragParams가 conversation override 덮어씀
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("stage6_overrides_stage5: request top_k=15 이 대화별 top_k=7 을 덮어씀")
    void stage6_overrides_stage5() {
        ConversationParamOverride override = new ConversationParamOverride();
        override.setConversationId("conv-123");
        override.setUserEmail("user@test.com");
        override.setParams(new HashMap<>(Map.of("top_k", 7)));
        when(conversationParamOverrideRepository
                .findByConversationIdAndUserEmail("conv-123", "user@test.com"))
                .thenReturn(Optional.of(override));

        Map<String, Object> ragParams = Map.of("top_k", 15);
        EffectiveParams result = resolver.resolve("user@test.com", "conv-123", ragParams);

        assertThat(result.values()).containsEntry("top_k", 15);
        assertThat(result.sources()).containsEntry("top_k", "stage6_request_override");
    }

    // -------------------------------------------------------------------------
    // 6. stage6_guard_b_keys_filtered: Guard B 키는 Stage 6에서 사전 필터링
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("stage6_guard_b_keys_filtered: sql_temperature 사용자 요청값은 Stage 6에서 필터링됨")
    void stage6_guard_b_keys_filtered() {
        Map<String, Object> ragParams = new HashMap<>();
        ragParams.put("sql_temperature", 0.9);  // Guard B 키 — 무시되어야 함
        ragParams.put("top_k", 8);              // 일반 키 — 적용되어야 함

        EffectiveParams result = resolver.resolve(null, null, ragParams);

        // sql_temperature 는 Stage 6 필터링 → stage1_hardcoded(0.1) 유지
        assertThat(result.sources()).containsEntry("sql_temperature", "stage1_hardcoded");
        assertThat(result.values()).containsEntry("sql_temperature", 0.1);
        // top_k 는 정상 적용
        assertThat(result.values()).containsEntry("top_k", 8);
        assertThat(result.sources()).containsEntry("top_k", "stage6_request_override");
    }

    // -------------------------------------------------------------------------
    // 7. guardA_clamps_over_max: max_tokens=9999 → Guard A max로 클램핑
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("guardA_clamps_over_max: max_tokens=9999 → Guard A max(4096)으로 클램핑")
    void guardA_clamps_over_max() {
        AdminParamLimit limit = AdminParamLimit.builder()
                .paramName("max_tokens")
                .minValue(new BigDecimal("256"))
                .maxValue(new BigDecimal("4096"))
                .guardType("A")
                .build();
        when(adminParamLimitRepository.findAll()).thenReturn(List.of(limit));

        // Stage 6 으로 9999 전달
        Map<String, Object> ragParams = Map.of("max_tokens", 9999);
        EffectiveParams result = resolver.resolve(null, null, ragParams);

        assertThat(result.values()).containsEntry("max_tokens", 4096);
        assertThat(result.sources().get("max_tokens")).contains("guard_a_clamp");
    }

    // -------------------------------------------------------------------------
    // 8. guardA_clamps_under_min: top_k=0 → Guard A min(1)으로 클램핑
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("guardA_clamps_under_min: top_k=0 → Guard A min(1)으로 클램핑")
    void guardA_clamps_under_min() {
        AdminParamLimit limit = AdminParamLimit.builder()
                .paramName("top_k")
                .minValue(new BigDecimal("1"))
                .maxValue(new BigDecimal("20"))
                .guardType("A")
                .build();
        when(adminParamLimitRepository.findAll()).thenReturn(List.of(limit));

        Map<String, Object> ragParams = Map.of("top_k", 0);
        EffectiveParams result = resolver.resolve(null, null, ragParams);

        assertThat(result.values()).containsEntry("top_k", 1);
        assertThat(result.sources().get("top_k")).contains("guard_a_clamp");
    }

    // -------------------------------------------------------------------------
    // 9. guardB_forces_locked: sql_temperature 는 Guard B 0.1 고정
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("guardB_forces_locked: Guard B sql_temperature=0.1 고정 — Stage 6 값 무시")
    void guardB_forces_locked() {
        AdminParamLimit guardB = AdminParamLimit.builder()
                .paramName("sql_temperature")
                .fixedValue(new BigDecimal("0.1"))
                .guardType("B")
                .build();
        when(adminParamLimitRepository.findAll()).thenReturn(List.of(guardB));

        // Stage 6 필터링으로 sql_temperature 는 ragParams 에서 제거됨
        // Guard B 적용: stage1_hardcoded(0.1) → guard_b_locked(0.1)
        EffectiveParams result = resolver.resolve(null, null, null);

        assertThat(result.values()).containsEntry("sql_temperature", 0.1);
        assertThat(result.sources()).containsEntry("sql_temperature", "guard_b_locked");
    }

    // -------------------------------------------------------------------------
    // 10. guardA_and_guardB_combined: Guard A + Guard B 혼합
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("guardA_and_guardB_combined: top_k Guard A 클램핑 + sql_temperature Guard B 고정 동시 적용")
    void guardA_and_guardB_combined() {
        AdminParamLimit guardA = AdminParamLimit.builder()
                .paramName("top_k")
                .minValue(new BigDecimal("1"))
                .maxValue(new BigDecimal("15"))
                .guardType("A")
                .build();
        AdminParamLimit guardB = AdminParamLimit.builder()
                .paramName("sql_temperature")
                .fixedValue(new BigDecimal("0.1"))
                .guardType("B")
                .build();
        when(adminParamLimitRepository.findAll()).thenReturn(List.of(guardA, guardB));

        // top_k=20 (Guard A max=15 초과) + sql_temperature (Guard B 고정)
        Map<String, Object> ragParams = Map.of("top_k", 20);
        EffectiveParams result = resolver.resolve(null, null, ragParams);

        // Guard A: top_k=20 → 15
        assertThat(result.values()).containsEntry("top_k", 15);
        assertThat(result.sources().get("top_k")).contains("guard_a_clamp");
        // Guard B: sql_temperature → 0.1 고정
        assertThat(result.values()).containsEntry("sql_temperature", 0.1);
        assertThat(result.sources()).containsEntry("sql_temperature", "guard_b_locked");
    }

    // -------------------------------------------------------------------------
    // 11. cache_hit_skips_db: conversationId 있으면 캐시 히트 시 repo 조회 안 함
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("cache_hit_skips_db: 캐시 히트 시 DB 조회 없이 캐시 결과 반환")
    void cache_hit_skips_db() {
        EffectiveParams cached = EffectiveParams.of(
                Map.of("top_k", 99),
                Map.of("top_k", "stage1_hardcoded")
        );
        when(parameterCacheService.get("user@test.com", "conv-123"))
                .thenReturn(Optional.of(cached));

        EffectiveParams result = resolver.resolve("user@test.com", "conv-123", null);

        assertThat(result.values()).containsEntry("top_k", 99);
        // DB 조회 없음
        verify(searchConfigMappingService, never()).getParams();
        verify(userParamProfileRepository, never()).findByUserEmail(any());
        verify(adminParamLimitRepository, never()).findAll();
    }

    // -------------------------------------------------------------------------
    // 12. null_conversationId_skips_cache: conversationId null이면 cache get/put 없음
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("null_conversationId_skips_cache: conversationId null이면 캐시 get/put 호출 없음")
    void null_conversationId_skips_cache() {
        EffectiveParams result = resolver.resolve("user@test.com", null, null);

        assertThat(result).isNotNull();
        verify(parameterCacheService, never()).get(any(), any());
        verify(parameterCacheService, never()).put(any(), any(), any());
    }
}
