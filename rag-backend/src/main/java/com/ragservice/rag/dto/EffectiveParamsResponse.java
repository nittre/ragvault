package com.ragservice.rag.dto;

import java.util.Map;

/**
 * GET /api/v1/user/conversations/{convId}/effective-params 응답 DTO.
 *
 * M5-3: ParameterResolver.resolve() 결과를 REST 응답 형태로 래핑.
 * ADR-0005: 최종 effective 값과 각 파라미터의 출처(source) 반환.
 *
 * @param effective 최종 적용 파라미터 맵 (13개 키)
 * @param sources   각 파라미터가 어느 Stage/Guard 에서 왔는지 (디버깅용)
 */
public record EffectiveParamsResponse(
        Map<String, Object> effective,
        Map<String, String> sources
) {}
