package com.ragservice.rag.controller;

import com.ragvault.core.domain.SqlExecutionLog;
import com.ragvault.core.repository.SqlExecutionLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SQL 실행 감사 로그 조회 Admin API.
 *
 * api:admin scope 필요 (SecurityConfig 에서 강제).
 *
 * ADR-0007: sql_execution_log 감사 추적
 * requirements/08-text-to-sql.md
 */
@RestController
@RequestMapping("/api/v1/admin/sql-logs")
@RequiredArgsConstructor
public class AdminSqlLogController {

    private final SqlExecutionLogRepository repository;

    /**
     * SQL 실행 로그 목록 조회.
     *
     * @param status           execution_status 필터 (null = 전체)
     * @param validationResult validation_result 필터 ("denied" 등, null = 전체)
     * @param limit            최대 반환 건수 (기본 100)
     */
    @GetMapping
    public List<SqlExecutionLog> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String validationResult,
            @RequestParam(required = false) String failureCategory,
            @RequestParam(defaultValue = "100") int limit) {

        if (failureCategory != null) {
            return repository
                    .findByFailureCategoryAndCreatedAtAfter(failureCategory, LocalDateTime.now().minusDays(30))
                    .stream()
                    .limit(limit)
                    .toList();
        }
        if (validationResult != null) {
            return repository
                    .findByValidationResultAndCreatedAtAfter(validationResult, LocalDateTime.now().minusDays(30))
                    .stream()
                    .limit(limit)
                    .toList();
        }
        if (status != null) {
            return repository
                    .findByExecutionStatusAndCreatedAtAfter(status, LocalDateTime.now().minusDays(30))
                    .stream()
                    .limit(limit)
                    .toList();
        }
        return repository.findAll(
                PageRequest.of(0, limit, Sort.by("createdAt").descending())
        ).getContent();
    }
}
