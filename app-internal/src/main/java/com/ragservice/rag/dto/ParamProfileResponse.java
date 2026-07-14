package com.ragservice.rag.dto;

import java.util.Map;

/**
 * GET /api/v1/user/param-profile 응답 DTO.
 *
 * 전역 기본값(Stage 1) + 관리자 한도(Guard A/B) 통합 응답. 사용자 설정은 세션(브라우저) 한정으로만
 * 관리되고 서버에 영구 저장하지 않으므로, 이 응답에는 전역 값만 담는다.
 *
 * @param defaults AdminDefaultsService.resolveDefaults() — Stage 1 기본값(admin_param_limits DB 기반, 하드코딩 없음)
 * @param limits   파라미터별 min/max/locked/lockedReason (Guard A/B 정보)
 */
public record ParamProfileResponse(
        Map<String, Object> defaults,
        Map<String, ParamLimitInfo> limits
) {}
