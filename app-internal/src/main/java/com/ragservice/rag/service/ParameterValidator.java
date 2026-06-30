package com.ragservice.rag.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 사용자 입력 파라미터 범위 · 타입 검증.
 *
 * requirements/09-user-parameter-tuning.md 섹션 5-1 기준.
 * ADR-0005: Guard B 파라미터(sql_temperature, sql_few_shot_examples, max_context_tokens)
 *           사용자 변경 시도 시 거부.
 */
@Component
public class ParameterValidator {

    /** Guard B 강제 고정 파라미터 — 사용자 입력 자체를 거부한다. */
    private static final Set<String> GUARD_B_KEYS = Set.of(
            "sql_temperature",
            "sql_few_shot_examples",
            "max_context_tokens"
    );

    /** force_path 허용 열거값 */
    private static final Set<String> FORCE_PATH_VALUES = Set.of(
            "AUTO", "FORCE_RAG", "FORCE_SQL", "FORCE_HYBRID"
    );

    /** hybrid_synthesis_style 허용 열거값 */
    private static final Set<String> HYBRID_STYLE_VALUES = Set.of(
            "BALANCED", "SQL_FIRST", "RAG_FIRST"
    );

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

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Guard B 파라미터 사용자 변경 시도 거부
            if (GUARD_B_KEYS.contains(key)) {
                return ValidationResult.fail(
                        key + " 는 관리자 정책으로 고정되어 있어 변경할 수 없습니다.");
            }

            ValidationResult result = validateSingle(key, value);
            if (!result.ok()) {
                return result;
            }
        }
        return ValidationResult.pass();
    }

    /**
     * 단일 파라미터 검증.
     * 알 수 없는 키는 통과 (Guard A/B 가 처리).
     */
    private ValidationResult validateSingle(String key, Object value) {
        return switch (key) {
            case "top_k" -> validateInteger(key, value, 1, 20);
            case "similarity_threshold" -> validateDouble(key, value, 0.0, 1.0);
            case "temperature" -> validateDouble(key, value, 0.0, 2.0);
            case "top_p" -> validateDouble(key, value, 0.0, 1.0);
            case "max_tokens" -> validateInteger(key, value, 100, 4096);
            case "query_timeout_sec" -> validateInteger(key, value, 5, 60);
            case "max_result_rows" -> validateInteger(key, value, 10, 10000);
            case "max_history_turns" -> validateInteger(key, value, 1, 50);
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
