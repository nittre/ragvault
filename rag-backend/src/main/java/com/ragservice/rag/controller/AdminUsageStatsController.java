package com.ragservice.rag.controller;

import com.ragservice.rag.repository.AuditLogRepository;
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
 */
@RestController
@RequestMapping("/api/v1/admin/usage-stats")
@RequiredArgsConstructor
public class AdminUsageStatsController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping("/daily")
    public ResponseEntity<?> daily(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now();
        LocalDateTime from = target.atStartOfDay();
        LocalDateTime to = target.plusDays(1).atStartOfDay();

        long total = auditLogRepository.findFiltered(null, null, from, to, Pageable.unpaged())
                .getTotalElements();
        long ragCount = auditLogRepository.findFiltered(null, "CHAT", from, to, Pageable.unpaged())
                .getTotalElements();
        long sqlCount = auditLogRepository.findFiltered(null, "SQL_QUERY", from, to, Pageable.unpaged())
                .getTotalElements();
        long fileCount = auditLogRepository.findFiltered(null, "FILE_UPLOAD", from, to, Pageable.unpaged())
                .getTotalElements();

        Map<String, Object> stats = Map.of(
                "date", target.toString(),
                "totalQueries", total,
                "breakdown", Map.of(
                        "RAG", ragCount,
                        "SQL", sqlCount,
                        "FILE_UPLOAD", fileCount
                )
        );
        return ResponseEntity.ok(stats);
    }
}
