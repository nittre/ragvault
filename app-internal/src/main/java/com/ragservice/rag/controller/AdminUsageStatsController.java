package com.ragservice.rag.controller;

import com.ragservice.rag.repository.AuditLogRepository;
import com.ragvault.core.repository.SqlExecutionLogRepository;
import com.ragvault.core.repository.WebSearchExecutionLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 사용량 통계 Admin API.
 * A7 시나리오: 일일 질의·경로별 분류 집계
 *
 * 접근 권한: api:admin scope (SecurityConfig 에서 강제)
 *
 * routing 과 executions 는 서로 다른 질문에 답한다:
 * - routing: 요청이 어떤 경로로 라우팅됐는지 (audit_log 기반, 총합 = totalQueries)
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

    @GetMapping("/daily")
    public ResponseEntity<?> daily(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now();
        LocalDateTime from = target.atStartOfDay();
        LocalDateTime to = target.plusDays(1).atStartOfDay();

        long total = auditLogRepository.findFiltered(null, null, from, to, Pageable.unpaged())
                .getTotalElements();
        long ragCount = auditLogRepository.findFiltered(null, "RAG", from, to, Pageable.unpaged())
                .getTotalElements();
        long sqlRoutingCount = auditLogRepository.findFiltered(null, "SQL_QUERY", from, to, Pageable.unpaged())
                .getTotalElements();
        long fileCount = auditLogRepository.findFiltered(null, "FILE_UPLOAD", from, to, Pageable.unpaged())
                .getTotalElements();
        long hybridCount = auditLogRepository.findFiltered(null, "HYBRID", from, to, Pageable.unpaged())
                .getTotalElements();
        long webSearchRoutingCount = auditLogRepository.findFiltered(null, "WEB_SEARCH", from, to, Pageable.unpaged())
                .getTotalElements();
        long otherCount = auditLogRepository.findFiltered(null, "OTHER", from, to, Pageable.unpaged())
                .getTotalElements();

        // 라우팅 경로와 무관하게 실제 실행된 횟수 (HYBRID 내부 실행 포함)
        long sqlExecutionCount = sqlExecutionLogRepository
                .countByValidationResultAndCreatedAtBetween("allowed", from, to);
        long webSearchExecutionCount = webSearchExecutionLogRepository
                .countByCreatedAtBetween(from, to);

        Map<String, Object> stats = Map.of(
                "date", target.toString(),
                "totalQueries", total,
                "routing", Map.of(
                        "RAG", ragCount,
                        "SQL_QUERY", sqlRoutingCount,
                        "FILE_UPLOAD", fileCount,
                        "HYBRID", hybridCount,
                        "WEB_SEARCH", webSearchRoutingCount,
                        "OTHER", otherCount
                ),
                "executions", Map.of(
                        "sqlQuery", sqlExecutionCount,
                        "webSearch", webSearchExecutionCount
                )
        );
        return ResponseEntity.ok(stats);
    }
}
