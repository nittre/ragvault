package com.ragvault.widget.dto;

import com.ragvault.core.util.DailyCountFiller;

import java.util.List;
import java.util.Map;

/**
 * 대화 로그 통계 응답 DTO.
 */
public record StatsDto(
        long totalCount,
        long last7dCount,
        long last30dCount,
        double contextHitRate30d,
        double blockedRate30d,
        List<DailyCountFiller.DailyCount> daily30d,
        Map<String, Long> routing30d,
        Map<String, Long> executions
) {
}
