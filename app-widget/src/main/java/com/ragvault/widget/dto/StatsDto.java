package com.ragvault.widget.dto;

import java.util.List;

/**
 * 대화 로그 통계 응답 DTO.
 */
public record StatsDto(
        long totalCount,
        long last7dCount,
        long last30dCount,
        double contextHitRate30d,
        double blockedRate30d,
        List<DailyCount> daily30d
) {
    public record DailyCount(String day, long count) {}
}
