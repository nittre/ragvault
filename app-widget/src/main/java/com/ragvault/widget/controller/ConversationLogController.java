package com.ragvault.widget.controller;

import com.ragvault.widget.dto.ConversationLogDto;
import com.ragvault.widget.dto.StatsDto;
import com.ragvault.widget.repository.ConversationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * GET /admin/conversations  — 대화 로그 조회 + 통계.
 *
 * /admin/** 경로는 SecurityConfig 에서 JWT 인증 필수로 보호됨 — 추가 처리 불필요.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/conversations")
@RequiredArgsConstructor
public class ConversationLogController {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ConversationLogRepository conversationLogRepository;

    /**
     * GET /admin/conversations?page=0&size=20&siteKey=...
     * siteKey 파라미터 있으면 해당 사이트로 필터링.
     */
    @GetMapping
    public Page<ConversationLogDto> list(
            @RequestParam(required = false) String siteKey,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        if (siteKey != null && !siteKey.isBlank()) {
            return conversationLogRepository.findBySiteKey(siteKey, pageable)
                    .map(ConversationLogDto::from);
        }
        return conversationLogRepository.findAll(pageable)
                .map(ConversationLogDto::from);
    }

    /**
     * GET /admin/conversations/stats
     */
    @GetMapping("/stats")
    public StatsDto stats() {
        Instant now = Instant.now();
        Instant from7d  = now.atZone(ZoneOffset.UTC).minusDays(7).toInstant();
        Instant from30d = now.atZone(ZoneOffset.UTC).minusDays(30).toInstant();

        long total       = conversationLogRepository.count();
        long last7d      = conversationLogRepository.countSince(from7d);
        long last30d     = conversationLogRepository.countSince(from30d);
        long context30d  = conversationLogRepository.countWithContextSince(from30d);
        long blocked30d  = conversationLogRepository.countBlockedSince(from30d);

        double contextHitRate = last30d > 0 ? (double) context30d / last30d : 0.0;
        double blockedRate    = last30d > 0 ? (double) blocked30d / last30d : 0.0;

        List<Object[]> rows = conversationLogRepository.dailyCountsSince(from30d);
        List<StatsDto.DailyCount> daily = rows.stream()
                .map(r -> new StatsDto.DailyCount(
                        toDateString(r[0]),
                        ((Number) r[1]).longValue()))
                .toList();

        return new StatsDto(total, last7d, last30d, contextHitRate, blockedRate, daily);
    }

    private String toDateString(Object dayObj) {
        if (dayObj instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate().format(DAY_FORMAT);
        }
        if (dayObj instanceof LocalDate ld) {
            return ld.format(DAY_FORMAT);
        }
        return String.valueOf(dayObj);
    }
}
