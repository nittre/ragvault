package com.ragservice.rag.service;

import com.ragservice.rag.domain.AdminParamLimit;
import com.ragservice.rag.dto.EffectiveParams;
import com.ragservice.rag.repository.AdminParamLimitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * ADR-0005 파라미터 우선순위 체인 + Guard A/B 구현.
 *
 * 적용 순서 (위→아래, 나중이 이김):
 *   Stage 1: 관리자 DB 기본값       (AdminDefaultsService — admin_param_limits.default_value, 서버 하드코딩 없음)
 *   Stage 2: 요청별 override         (request body rag_params, 세션 한정 사용자 설정)
 *   Guard A: 범위 클램핑 (soft)      (admin_param_limits guard_type='A')
 *   Guard B: 강제 고정 (hard)        (admin_param_limits guard_type='B')
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParameterResolver {

    /** Stage 2에서 사전 필터링할 Guard B 키. Guard B가 나중에 덮어쓰지만 사전 필터링이 더 명확. */
    private static final Set<String> GUARD_B_USER_KEYS = Set.of(
            "sql_temperature",
            "sql_few_shot_examples",
            "max_context_tokens"
    );

    private final AdminDefaultsService adminDefaultsService;
    private final AdminParamLimitRepository adminParamLimitRepository;

    /**
     * 우선순위 체인으로 최종 파라미터를 결정한다.
     *
     * @param requestRagParams request body의 rag_params (null 허용)
     * @return 최종 파라미터 + source 메타데이터
     */
    public EffectiveParams resolve(Map<String, Object> requestRagParams) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();

        // Stage 1: 하드코딩 기본값
        applyStage1(params, sources);
        // Stage 2: request body rag_params
        applyStage2(params, sources, requestRagParams);
        // Guard A: 범위 클램핑
        applyGuardA(params, sources);
        // Guard B: 강제 고정
        applyGuardB(params, sources);

        EffectiveParams result = EffectiveParams.of(params, sources);
        log.debug("ParameterResolver resolved: params={}", result.values().keySet());

        return result;
    }

    // -------------------------------------------------------------------------
    // Stage methods
    // -------------------------------------------------------------------------

    private void applyStage1(Map<String, Object> params, Map<String, String> sources) {
        adminDefaultsService.resolveDefaults().forEach((k, v) -> {
            params.put(k, v);
            sources.put(k, "stage1_admin_default");
        });
    }

    /**
     * Stage 2: request body rag_params.
     * Guard B 키(sql_temperature, sql_few_shot_examples, max_context_tokens)는 사전 필터링.
     * Guard B가 나중에 덮어쓰더라도, 사용자 의도 자체를 무시하는 것이 더 명확하다.
     */
    private void applyStage2(Map<String, Object> params, Map<String, String> sources,
                              Map<String, Object> requestRagParams) {
        if (requestRagParams == null || requestRagParams.isEmpty()) {
            return;
        }
        requestRagParams.forEach((k, v) -> {
            if (GUARD_B_USER_KEYS.contains(k)) {
                log.debug("Stage 2: Guard B key '{}' filtered from request params", k);
                return; // Guard B 키는 무시
            }
            params.put(k, v);
            sources.put(k, "stage2_request_override");
        });
    }

    // -------------------------------------------------------------------------
    // Guard methods
    // -------------------------------------------------------------------------

    /**
     * Guard A: 범위 클램핑 (soft, silent).
     * guard_type='A' 인 항목에 min/max 클램핑. fixedValue 는 null.
     */
    private void applyGuardA(Map<String, Object> params, Map<String, String> sources) {
        adminParamLimitRepository.findAll().forEach(limit -> {
            if (limit.isLocked()) {
                return; // Guard B는 applyGuardB에서 처리
            }
            String key = limit.getParamName();
            Object currentValue = params.get(key);
            if (!(currentValue instanceof Number num)) {
                return; // 숫자 파라미터만 클램핑
            }
            double val = num.doubleValue();
            boolean clamped = false;

            if (limit.getMinValue() != null && val < limit.getMinValue().doubleValue()) {
                val = limit.getMinValue().doubleValue();
                clamped = true;
            }
            if (limit.getMaxValue() != null && val > limit.getMaxValue().doubleValue()) {
                val = limit.getMaxValue().doubleValue();
                clamped = true;
            }

            if (clamped) {
                // 원래 타입 보존 (Integer 파라미터는 Integer로)
                Object clampedValue = restoreType(currentValue, val);
                params.put(key, clampedValue);
                sources.put(key, sources.getOrDefault(key, "unknown") + "+guard_a_clamp");
                log.debug("Guard A clamped: key={}, original={}, clamped={}", key,
                        ((Number) currentValue).doubleValue(), val);
            }
        });
    }

    /**
     * Guard B: 강제 고정 (hard, override all).
     * guard_type='B' 인 항목의 fixedValue 로 강제 덮어쓰기.
     * Stage 1~2 + Guard A 결과를 모두 무시한다.
     */
    private void applyGuardB(Map<String, Object> params, Map<String, String> sources) {
        adminParamLimitRepository.findAll().forEach(limit -> {
            if (!limit.isLocked() || limit.getFixedValue() == null) {
                return;
            }
            String key = limit.getParamName();
            Object currentValue = params.get(key);
            if (!(currentValue instanceof Number)) {
                // force_path/hybrid_synthesis_style 같은 문자열(enum) 파라미터는 fixed_value(NUMERIC)로
                // 강제할 수 없다 — 관리자가 실수로 guard_type='B'를 켜도 값을 훼손하지 않도록 방어.
                return;
            }
            double fixedDouble = limit.getFixedValue().doubleValue();

            // 파라미터 타입에 따라 Integer/Double 변환
            Object fixedValue = currentValue instanceof Integer
                    ? (int) fixedDouble
                    : fixedDouble;

            params.put(key, fixedValue);
            sources.put(key, "guard_b_locked");
            log.debug("Guard B locked: key={}, fixedValue={}", key, fixedValue);
        });
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * 클램핑된 double 값을 원래 파라미터 타입으로 복원.
     * 원래 값이 Integer 이면 int 로, 아니면 double 그대로.
     */
    private Object restoreType(Object original, double clampedDouble) {
        if (original instanceof Integer) {
            return (int) clampedDouble;
        }
        return clampedDouble;
    }
}
