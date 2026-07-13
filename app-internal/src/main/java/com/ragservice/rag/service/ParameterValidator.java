package com.ragservice.rag.service;

import com.ragservice.rag.domain.AdminParamLimit;
import com.ragservice.rag.repository.AdminParamLimitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 사용자 입력 파라미터 범위 · 타입 검증.
 *
 * requirements/09-user-parameter-tuning.md 섹션 5-1 기준.
 * ADR-0005: admin_param_limits 가 유일한 진실 소스 — 서버 코드에는 범위 폴백을 두지 않는다.
 *           숫자형 파라미터인데 admin_param_limits 에 min/max 행이 없으면, 이는 관리자 설정
 *           누락이므로 조용히 폴백하지 않고 즉시 500으로 실패시킨다.
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

    private static final Set<String> INT_KEYS = ParamTypeRegistry.INT_KEYS;
    private static final Set<String> DOUBLE_KEYS = ParamTypeRegistry.DOUBLE_KEYS;

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
     * 숫자형 키(INT_KEYS/DOUBLE_KEYS)는 admin_param_limits 의 min/max가 반드시 있어야 한다(폴백 없음).
     * 그 외 admin_param_limits 에만 존재하는 숫자 키는 그 min/max로 검증. 알 수 없는 키는 통과(Guard A/B 가 처리).
     */
    private ValidationResult validateSingle(String key, Object value, AdminParamLimit limit) {
        if (INT_KEYS.contains(key)) {
            requireRange(key, limit);
            return validateInteger(key, value, limit.getMinValue().intValue(), limit.getMaxValue().intValue());
        }
        if (DOUBLE_KEYS.contains(key)) {
            requireRange(key, limit);
            return validateDouble(key, value, limit.getMinValue().doubleValue(), limit.getMaxValue().doubleValue());
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

    /**
     * 숫자형 파라미터는 admin_param_limits 에 min/max 가 반드시 설정돼 있어야 한다.
     * 서버 코드에는 범위 폴백을 두지 않으므로, 없으면 관리자 설정 누락으로 간주해 즉시 실패시킨다.
     */
    private void requireRange(String key, AdminParamLimit limit) {
        if (limit == null || limit.getMinValue() == null || limit.getMaxValue() == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "관리자가 파라미터 '" + key + "'의 허용 범위(min/max)를 설정하지 않았습니다. "
                            + "관리자 화면에서 파라미터 한도를 먼저 설정해주세요.");
        }
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
