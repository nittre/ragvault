package com.ragservice.rag.service;

import com.ragservice.rag.domain.AdminParamLimit;
import com.ragservice.rag.repository.AdminParamLimitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 사용자 입력 파라미터 범위 · 타입 검증.
 *
 * requirements/09-user-parameter-tuning.md 섹션 5-1 기준.
 * ADR-0005: admin_param_limits 가 유일한 진실 소스. DB에 해당 키 행이 있으면
 *           그 min/max·잠금여부를 사용하고, 없으면 하드코딩 값을 폴백으로 사용.
 */
@Component
@RequiredArgsConstructor
public class ParameterValidator {

    private final AdminParamLimitRepository adminParamLimitRepository;

    /** force_path 허용 열거값 */
    private static final Set<String> FORCE_PATH_VALUES = Set.of(
            "AUTO", "FORCE_RAG", "FORCE_SQL", "FORCE_HYBRID"
    );

    /** hybrid_synthesis_style 허용 열거값 */
    private static final Set<String> HYBRID_STYLE_VALUES = Set.of(
            "BALANCED", "SQL_FIRST", "RAG_FIRST"
    );

    /** 정수형 파라미터 하드코딩 범위 폴백 — admin_param_limits 에 행이 없을 때만 사용. */
    private static final Map<String, int[]> INT_RANGE_FALLBACKS = Map.of(
            "top_k", new int[]{1, 20},
            "max_tokens", new int[]{100, 4096},
            "query_timeout_sec", new int[]{5, 60},
            "max_result_rows", new int[]{10, 10000},
            "max_history_turns", new int[]{1, 50}
    );

    /** 실수형 파라미터 하드코딩 범위 폴백 — admin_param_limits 에 행이 없을 때만 사용. */
    private static final Map<String, double[]> DOUBLE_RANGE_FALLBACKS = Map.of(
            "similarity_threshold", new double[]{0.0, 1.0},
            "temperature", new double[]{0.0, 2.0},
            "top_p", new double[]{0.0, 1.0}
    );

    /** DB 조회 없이 범위 검증이 필요한 정수 키(하드코딩 폴백 대상 목록과 동일). */
    private static final Set<String> INT_KEYS = INT_RANGE_FALLBACKS.keySet();

    /** DB 조회 없이 범위 검증이 필요한 실수 키(하드코딩 폴백 대상 목록과 동일). */
    private static final Set<String> DOUBLE_KEYS = DOUBLE_RANGE_FALLBACKS.keySet();

    /**
     * 검증 결과 DTO.
     */
    public record ValidationResult(boolean ok, String reason) {
        public static ValidationResult pass() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    /**
     * 파라미터 맵 전체 검증.
     * null-safe: params가 null이면 pass().
     *
     * @param params 사용자가 전달한 파라미터 맵
     * @return 첫 번째 실패 항목의 ValidationResult, 모두 통과시 pass()
     */
    public ValidationResult validate(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return ValidationResult.pass();
        }

        // N+1 방지: 키마다 조회하지 않고 한 번만 findAll() 후 맵으로 조회.
        Map<String, AdminParamLimit> limitsByName = adminParamLimitRepository.findAll().stream()
                .collect(Collectors.toMap(AdminParamLimit::getParamName, Function.identity()));

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            AdminParamLimit limit = limitsByName.get(key);

            // Guard B(관리자 강제 고정) 파라미터 사용자 변경 시도 거부
            if (limit != null && limit.isLocked()) {
                return ValidationResult.fail(
                        key + " 는 관리자 정책으로 고정되어 있어 변경할 수 없습니다.");
            }

            ValidationResult result = validateSingle(key, value, limit);
            if (!result.ok()) {
                return result;
            }
        }
        return ValidationResult.pass();
    }

    /**
     * 단일 파라미터 검증.
     * admin_param_limits 에 해당 키 행(Guard A)이 있으면 그 min/max를 사용하고,
     * 없으면 하드코딩 범위를 폴백으로 사용한다. 알 수 없는 키는 통과 (Guard A/B 가 처리).
     */
    private ValidationResult validateSingle(String key, Object value, AdminParamLimit limit) {
        if (INT_KEYS.contains(key)) {
            int[] fallback = INT_RANGE_FALLBACKS.get(key);
            int min = limit != null ? limit.getMinValue().intValue() : fallback[0];
            int max = limit != null ? limit.getMaxValue().intValue() : fallback[1];
            return validateInteger(key, value, min, max);
        }
        if (DOUBLE_KEYS.contains(key)) {
            double[] fallback = DOUBLE_RANGE_FALLBACKS.get(key);
            double min = limit != null ? limit.getMinValue().doubleValue() : fallback[0];
            double max = limit != null ? limit.getMaxValue().doubleValue() : fallback[1];
            return validateDouble(key, value, min, max);
        }
        if (limit != null && limit.getMinValue() != null && limit.getMaxValue() != null) {
            // admin_param_limits 에만 존재하는 알려지지 않은 숫자 키 — 실수 범위로 검증.
            return validateDouble(key, value, limit.getMinValue().doubleValue(), limit.getMaxValue().doubleValue());
        }
        return switch (key) {
            case "force_path" -> validateEnum(key, value, FORCE_PATH_VALUES);
            case "hybrid_synthesis_style" -> validateEnum(key, value, HYBRID_STYLE_VALUES);
            default -> ValidationResult.pass(); // 알 수 없는 키는 통과
        };
    }

    private ValidationResult validateInteger(String key, Object value, int min, int max) {
        if (value == null) {
            return ValidationResult.fail(key + " 값이 null 입니다.");
        }
        int intVal;
        try {
            intVal = ((Number) value).intValue();
        } catch (ClassCastException e) {
            return ValidationResult.fail(key + " 는 정수 타입이어야 합니다.");
        }
        if (intVal < min || intVal > max) {
            return ValidationResult.fail(
                    String.format("%s 는 %d ~ %d 사이여야 합니다. (입력값: %d)", key, min, max, intVal));
        }
        return ValidationResult.pass();
    }

    private ValidationResult validateDouble(String key, Object value, double min, double max) {
        if (value == null) {
            return ValidationResult.fail(key + " 값이 null 입니다.");
        }
        double dblVal;
        try {
            dblVal = ((Number) value).doubleValue();
        } catch (ClassCastException e) {
            return ValidationResult.fail(key + " 는 숫자 타입이어야 합니다.");
        }
        if (dblVal < min || dblVal > max) {
            return ValidationResult.fail(
                    String.format("%s 는 %.1f ~ %.1f 사이여야 합니다. (입력값: %.4f)", key, min, max, dblVal));
        }
        return ValidationResult.pass();
    }

    private ValidationResult validateEnum(String key, Object value, Set<String> allowed) {
        if (value == null) {
            return ValidationResult.fail(key + " 값이 null 입니다.");
        }
        if (!(value instanceof String strVal)) {
            return ValidationResult.fail(key + " 는 문자열 타입이어야 합니다.");
        }
        if (!allowed.contains(strVal)) {
            return ValidationResult.fail(
                    String.format("%s 는 %s 중 하나여야 합니다. (입력값: %s)", key, allowed, strVal));
        }
        return ValidationResult.pass();
    }
}
