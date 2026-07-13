package com.ragservice.rag.dto;

import java.math.BigDecimal;

/**
 * 파라미터 한 개의 허용 범위 + 잠금 여부.
 *
 * M5-3: GET /api/v1/user/param-profile 응답에 포함 (ParamProfileResponse.limits).
 * ADR-0005: Guard A(범위 클램핑) / Guard B(강제 고정) 구분.
 */
public record ParamLimitInfo(
        BigDecimal minValue,
        BigDecimal maxValue,
        boolean locked,
        String lockedReason,
        BigDecimal fixedValue
) {}
