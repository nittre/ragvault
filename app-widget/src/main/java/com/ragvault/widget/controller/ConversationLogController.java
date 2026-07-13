package com.ragvault.widget.controller;

import com.ragvault.core.repository.SqlExecutionLogRepository;
import com.ragvault.core.util.DailyCountFiller;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final ConversationLogRepository conversationLogRepository;
    private final SqlExecutionLogRepository sqlExecutionLogRepository;

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
        List<DailyCountFiller.DailyCount> daily = DailyCountFiller.fill(rows, 30);

        Map<String, Long> routing = new LinkedHashMap<>(Map.of(
                "RAG", 0L, "SQL", 0L, "HYBRID", 0L, "REJECT", 0L, "OTHER", 0L));
        for (Object[] row : conversationLogRepository.actionCountsSince(from30d)) {
            routing.put((String) row[0], ((Number) row[1]).longValue());
        }

        // SQL 실행 횟수 (최근 30일) — 챗 서비스 어드민의 '실행 통계'와 동일한 체계.
        // widget_db 에는 web_search_execution_log 테이블이 없으므로(위젯은 웹검색 라우팅 미지원) sqlQuery 만 집계한다.
        LocalDateTime sqlFrom30d = LocalDateTime.now().minusDays(30);
        long sqlExecutionCount30d = sqlExecutionLogRepository
                .countByValidationResultAndCreatedAtBetween("allowed", sqlFrom30d, LocalDateTime.now());
        Map<String, Long> executions = Map.of("sqlQuery", sqlExecutionCount30d);

        return new StatsDto(total, last7d, last30d, contextHitRate, blockedRate, daily, routing, executions);
    }
}
