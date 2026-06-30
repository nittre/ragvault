package com.ragservice.rag.dto;

import java.util.Map;

/**
 * GET /api/v1/user/param-profile 응답 DTO.
 *
 * M5-3: 현재 사용자 프로필 + 시스템 기본값 + 관리자 한계 통합 응답.
 * ADR-0005: 7단계 우선순위 체인에서 Stage 4(사용자 프로필)에 해당.
 *
 * @param params   현재 사용자 프로필 저장값 (없으면 빈 맵)
 * @param defaults HardcodedDefaults.get() — Stage 1 fallback 기본값
 * @param limits   파라미터별 min/max/locked/lockedReason (Guard A/B 정보)
 */
public record ParamProfileResponse(
        Map<String, Object> params,
        Map<String, Object> defaults,
        Map<String, ParamLimitInfo> limits
) {}
