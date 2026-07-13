package com.ragservice.rag.controller;

import com.ragservice.rag.repository.AuditLogRepository;
import com.ragvault.core.repository.SqlExecutionLogRepository;
import com.ragvault.core.repository.WebSearchExecutionLogRepository;
import com.ragvault.core.util.DailyCountFiller;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 사용량 통계 Admin API.
 * A7 시나리오: 최근 30일 질의·경로별 분류 집계 (위젯 서비스와 통일된 기간 기준)
 *
 * 접근 권한: api:admin scope (SecurityConfig 에서 강제)
 *
 * routing 과 executions 는 서로 다른 질문에 답한다:
 * - routing: 요청이 어떤 경로로 라우팅됐는지 (audit_log 기반, 총합 = last30dCount)
 * - executions: SQL/웹검색이 실제로 몇 번 실행됐는지 (sql_execution_log / web_search_execution_log 기반).
 *   HYBRID 요청 내부에서 실행된 것도 포함되므로 routing 과 합계가 다를 수 있다.
 */
@RestController
@RequestMapping("/api/v1/admin/usage-stats")
@RequiredArgsConstructor
public class AdminUsageStatsController {

    private final AuditLogRepository auditLogRepository;
    private final SqlExecutionLogRepository sqlExecutionLogRepository;
    private final WebSearchExecutionLogRepository webSearchExecutionLogRepository;

    /**
     * 위젯 서비스(app-widget) 어드민 사용자 통계와 지표 체계를 통일한 요약 API.
     * totalCount/last7dCount/last30dCount/contextHitRate30d/blockedRate30d/daily30d 는
     * 위젯의 StatsDto 와 동일한 필드명이며, routing/executions 는 챗 서비스 고유의
     * 라우팅 분류·실행 통계(모두 최근 30일 기준)이다.
     */
    @GetMapping("/summary")
    public ResponseEntity<?> summary() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from7d = now.minusDays(7);
        LocalDateTime from30d = now.minusDays(30);

        long total = auditLogRepository.countChatQueries();
        long last7d = auditLogRepository.countChatQueriesSince(from7d);
        long last30d = auditLogRepository.countChatQueriesSince(from30d);
        long context30d = auditLogRepository.countWithContextSince(from30d);
        long blocked30d = auditLogRepository.countBlockedSince(from30d);

        double contextHitRate30d = last30d > 0 ? (double) context30d / last30d : 0.0;
        double blockedRate30d = last30d > 0 ? (double) blocked30d / last30d : 0.0;

        List<Object[]> rows = auditLogRepository.dailyCountsSince(from30d);
        List<DailyCountFiller.DailyCount> daily30d = DailyCountFiller.fill(rows, 30);

        long ragCount30d = auditLogRepository.findFiltered(null, "RAG", from30d, null, Pageable.unpaged())
                .getTotalElements();
        long sqlRoutingCount30d = auditLogRepository.findFiltered(null, "SQL_QUERY", from30d, null, Pageable.unpaged())
                .getTotalElements();
        long fileCount30d = auditLogRepository.findFiltered(null, "FILE_UPLOAD", from30d, null, Pageable.unpaged())
                .getTotalElements();
        long hybridCount30d = auditLogRepository.findFiltered(null, "HYBRID", from30d, null, Pageable.unpaged())
                .getTotalElements();
        long webSearchRoutingCount30d = auditLogRepository.findFiltered(null, "WEB_SEARCH", from30d, null, Pageable.unpaged())
                .getTotalElements();
        long otherCount30dRaw = auditLogRepository.findFiltered(null, "OTHER", from30d, null, Pageable.unpaged())
                .getTotalElements();
        long rejectCount30d = auditLogRepository.countRejectedSince(from30d, null);
        long otherCount30d = otherCount30dRaw - rejectCount30d;

        // 라우팅 경로와 무관하게 실제 실행된 횟수 (HYBRID 내부 실행 포함), 최근 30일 기준
        long sqlExecutionCount30d = sqlExecutionLogRepository
                .countByValidationResultAndCreatedAtBetween("allowed", from30d, now);
        long webSearchExecutionCount30d = webSearchExecutionLogRepository
                .countByCreatedAtBetween(from30d, now);

        Map<String, Object> summary = Map.of(
                "totalCount", total,
                "last7dCount", last7d,
                "last30dCount", last30d,
                "contextHitRate30d", contextHitRate30d,
                "blockedRate30d", blockedRate30d,
                "daily30d", daily30d,
                "routing", Map.of(
                        "RAG", ragCount30d,
                        "SQL_QUERY", sqlRoutingCount30d,
                        "FILE_UPLOAD", fileCount30d,
                        "HYBRID", hybridCount30d,
                        "WEB_SEARCH", webSearchRoutingCount30d,
                        "REJECT", rejectCount30d,
                        "OTHER", otherCount30d
                ),
                "executions", Map.of(
                        "sqlQuery", sqlExecutionCount30d,
                        "webSearch", webSearchExecutionCount30d
                )
        );
        return ResponseEntity.ok(summary);
    }
}
