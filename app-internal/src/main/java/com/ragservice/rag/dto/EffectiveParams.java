package com.ragservice.rag.dto;

import java.util.Map;

/**
 * ParameterResolver가 반환하는 최종 파라미터 결과.
 *
 * values  : 실제 적용값 (13개 파라미터 키 우주)
 * sources : 각 키가 어느 Stage에서 왔는지 (디버깅용)
 *
 * ADR-0005: 7단계 우선순위 체인 + Guard A/B.
 */
public record EffectiveParams(
        Map<String, Object> values,
        Map<String, String> sources
) {
    public static EffectiveParams of(Map<String, Object> values, Map<String, String> sources) {
        return new EffectiveParams(Map.copyOf(values), Map.copyOf(sources));
    }
}
