package com.ragservice.rag.service;

import com.ragservice.rag.domain.AdminParamLimit;
import com.ragservice.rag.repository.AdminParamLimitRepository;
import com.ragservice.rag.dto.EffectiveParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * ParameterResolver 단위 테스트.
 *
 * ADR-0005 파라미터 우선순위 체인(Stage 1: 전역 기본값, Stage 2: 요청별/세션 값) + Guard A/B 검증.
 * Stage 1 은 AdminDefaultsService(admin_param_limits.default_value) 기반이라 — 서버 코드에
 * 하드코딩 폴백이 없다 — 13개 파라미터 전부가 admin_param_limits 에 존재해야 resolve() 가 성공한다.
 * baselineLimits() 가 그 13개 row 를 갖춘 기준 픽스처를 제공한다.
 */
@ExtendWith(MockitoExtension.class)
class ParameterResolverTest {

    @Mock
    private AdminParamLimitRepository adminParamLimitRepository;

    private ParameterResolver resolver;

    @BeforeEach
    void setUp() {
        AdminDefaultsService adminDefaultsService = new AdminDefaultsService(adminParamLimitRepository);
        resolver = new ParameterResolver(adminDefaultsService, adminParamLimitRepository);
        // 기본: admin_param_limits 에 13개 파라미터 전부 기준값으로 설정돼 있음 (ADR-0005: 하드코딩 없음)
        lenient().when(adminParamLimitRepository.findAll()).thenReturn(baselineLimits());
    }

    /** 13개 파라미터 전부 default_value가 설정된 기준 픽스처 — V37 마이그레이션 시드값과 동일. */
    private static List<AdminParamLimit> baselineLimits() {
        return new ArrayList<>(List.of(
                limit("top_k", "5", "1", "20", null, "A"),
                limit("similarity_threshold", "0.65", "0", "1", null, "A"),
                limit("temperature", "0.7", "0", "2", null, "A"),
                limit("max_tokens", "2000", "256", "8192", null, "A"),
                limit("top_p", "0.9", "0", "1", null, "A"),
                limit("max_history_turns", "10", "1", "50", null, "A"),
                limit("query_timeout_sec", "10", "5", "20", null, "A"),
                limit("max_result_rows", "1000", "10", "2000", null, "A"),
                limit("sql_temperature", "0.1", null, null, "0.1", "B"),
                limit("sql_few_shot_examples", "5", null, null, "5", "B"),
                limit("max_context_tokens", "5000", null, null, "5000", "B"),
                limit("force_path", "AUTO", null, null, null, "A"),
                limit("hybrid_synthesis_style", "BALANCED", null, null, null, "A")
        ));
    }

    private static AdminParamLimit limit(String paramName, String defaultValue,
                                          String min, String max, String fixed, String guardType) {
        return AdminParamLimit.builder()
                .paramName(paramName)
                .defaultValue(defaultValue)
                .minValue(min != null ? new BigDecimal(min) : null)
                .maxValue(max != null ? new BigDecimal(max) : null)
                .fixedValue(fixed != null ? new BigDecimal(fixed) : null)
                .guardType(guardType)
                .build();
    }

    /** baselineLimits() 에서 paramName 이 일치하는 row 를 override 로 교체한 목록. */
    private static List<AdminParamLimit> baselineWithOverride(AdminParamLimit override) {
        List<AdminParamLimit> list = new ArrayList<>();
        for (AdminParamLimit l : baselineLimits()) {
            list.add(l.getParamName().equals(override.getParamName()) ? override : l);
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // 1. stage1_only: 모든 Stage 비어있으면 admin_param_limits.default_value 반환
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("stage1_only: 모든 Stage 비어있으면 admin_param_limits.default_value 기본값 반환")
    void stage1_only() {
        EffectiveParams result = resolver.resolve(null);

        assertThat(result.values()).containsEntry("top_k", 5);
        assertThat(result.values()).containsEntry("similarity_threshold", 0.65);
        assertThat(result.values()).containsEntry("temperature", 0.7);
        assertThat(result.values()).containsEntry("force_path", "AUTO");
        assertThat(result.sources()).containsEntry("top_k", "stage1_admin_default");
        assertThat(result.sources()).containsEntry("force_path", "stage1_admin_default");
        // Guard B 3종(sql_temperature/sql_few_shot_examples/max_context_tokens)은 baseline에서도
        // guard_type='B'로 고정돼 있으므로 stage1이 아니라 guard_b_locked가 최종 source가 된다.
        assertThat(result.sources()).containsEntry("sql_temperature", "guard_b_locked");
        assertThat(result.sources()).containsEntry("sql_few_shot_examples", "guard_b_locked");
        assertThat(result.sources()).containsEntry("max_context_tokens", "guard_b_locked");
    }

    // -------------------------------------------------------------------------
    // 2. stage2_overrides_stage1: request body(세션 파라미터)가 전역 기본값을 덮어씀
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("stage2_overrides_stage1: request top_k=15 가 admin 기본값 top_k=5 를 덮어씀")
    void stage2_overrides_stage1() {
        Map<String, Object> ragParams = Map.of("top_k", 15);
        EffectiveParams result = resolver.resolve(ragParams);

        assertThat(result.values()).containsEntry("top_k", 15);
        assertThat(result.sources()).containsEntry("top_k", "stage2_request_override");
    }

    // -------------------------------------------------------------------------
    // 3. stage2_guard_b_keys_filtered: Guard B 키는 Stage 2에서 사전 필터링
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("stage2_guard_b_keys_filtered: sql_temperature 사용자 요청값은 Stage 2에서 필터링됨")
    void stage2_guard_b_keys_filtered() {
        Map<String, Object> ragParams = new HashMap<>();
        ragParams.put("sql_temperature", 0.9);  // Guard B 키 — 무시되어야 함
        ragParams.put("top_k", 8);              // 일반 키 — 적용되어야 함

        EffectiveParams result = resolver.resolve(ragParams);

        // sql_temperature 는 Stage 2 필터링 후에도 Guard B가 최종적으로 0.1 고정
        assertThat(result.sources()).containsEntry("sql_temperature", "guard_b_locked");
        assertThat(result.values()).containsEntry("sql_temperature", 0.1);
        // top_k 는 정상 적용
        assertThat(result.values()).containsEntry("top_k", 8);
        assertThat(result.sources()).containsEntry("top_k", "stage2_request_override");
    }

    // -------------------------------------------------------------------------
    // 4. guardA_clamps_over_max: max_tokens=9999 → Guard A max로 클램핑
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("guardA_clamps_over_max: max_tokens=9999 → Guard A max(4096)으로 클램핑")
    void guardA_clamps_over_max() {
        AdminParamLimit override = limit("max_tokens", "2000", "256", "4096", null, "A");
        when(adminParamLimitRepository.findAll()).thenReturn(baselineWithOverride(override));

        // Stage 2 으로 9999 전달
        Map<String, Object> ragParams = Map.of("max_tokens", 9999);
        EffectiveParams result = resolver.resolve(ragParams);

        assertThat(result.values()).containsEntry("max_tokens", 4096);
        assertThat(result.sources().get("max_tokens")).contains("guard_a_clamp");
    }

    // -------------------------------------------------------------------------
    // 5. guardA_clamps_under_min: top_k=0 → Guard A min(1)으로 클램핑
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("guardA_clamps_under_min: top_k=0 → Guard A min(1)으로 클램핑")
    void guardA_clamps_under_min() {
        // baseline 의 top_k min=1, max=20 그대로 사용

        Map<String, Object> ragParams = Map.of("top_k", 0);
        EffectiveParams result = resolver.resolve(ragParams);

        assertThat(result.values()).containsEntry("top_k", 1);
        assertThat(result.sources().get("top_k")).contains("guard_a_clamp");
    }

    // -------------------------------------------------------------------------
    // 6. guardB_forces_locked: sql_temperature 는 Guard B 0.1 고정
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("guardB_forces_locked: Guard B sql_temperature=0.1 고정 — Stage 2 값 무시")
    void guardB_forces_locked() {
        // baseline 에 이미 sql_temperature Guard B(fixedValue=0.1)가 포함돼 있음

        EffectiveParams result = resolver.resolve(null);

        assertThat(result.values()).containsEntry("sql_temperature", 0.1);
        assertThat(result.sources()).containsEntry("sql_temperature", "guard_b_locked");
    }

    // -------------------------------------------------------------------------
    // 7. guardA_and_guardB_combined: Guard A + Guard B 혼합
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("guardA_and_guardB_combined: top_k Guard A 클램핑 + sql_temperature Guard B 고정 동시 적용")
    void guardA_and_guardB_combined() {
        AdminParamLimit topKOverride = limit("top_k", "5", "1", "15", null, "A");
        when(adminParamLimitRepository.findAll()).thenReturn(baselineWithOverride(topKOverride));

        // top_k=20 (Guard A max=15 초과) + sql_temperature (baseline Guard B 고정)
        Map<String, Object> ragParams = Map.of("top_k", 20);
        EffectiveParams result = resolver.resolve(ragParams);

        // Guard A: top_k=20 → 15
        assertThat(result.values()).containsEntry("top_k", 15);
        assertThat(result.sources().get("top_k")).contains("guard_a_clamp");
        // Guard B: sql_temperature → 0.1 고정
        assertThat(result.values()).containsEntry("sql_temperature", 0.1);
        assertThat(result.sources()).containsEntry("sql_temperature", "guard_b_locked");
    }

    // -------------------------------------------------------------------------
    // 8. forcePath_allowedFromRequestOverride: force_path는 Stage 2(요청/세션 단위)에서는 정상 적용
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("forcePath_allowedFromRequestOverride: 요청 body의 force_path는 정상 적용됨")
    void forcePath_allowedFromRequestOverride() {
        Map<String, Object> ragParams = Map.of("force_path", "FORCE_SQL");
        EffectiveParams result = resolver.resolve(ragParams);

        assertThat(result.values()).containsEntry("force_path", "FORCE_SQL");
        assertThat(result.sources()).containsEntry("force_path", "stage2_request_override");
    }

    // -------------------------------------------------------------------------
    // 9. missingDefaultValue_failsFast: admin_param_limits 에 없는 파라미터가 있으면 즉시 실패(500)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("missingDefaultValue_failsFast: 관리자가 기본값을 설정 안 한 파라미터가 있으면 resolve() 가 예외를 던짐")
    void missingDefaultValue_failsFast() {
        // top_p 행 자체를 빼서 "관리자가 아직 설정 안 함" 상황을 재현
        List<AdminParamLimit> incomplete = baselineLimits().stream()
                .filter(l -> !l.getParamName().equals("top_p"))
                .toList();
        when(adminParamLimitRepository.findAll()).thenReturn(incomplete);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> resolver.resolve(null));
    }
}
