package com.ragvault.core.repository;

import com.ragvault.core.domain.WebSearchExecutionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * web_search_execution_log 리포지토리.
 * WEB_SEARCH 실행 감사 로그 조회 (sql_execution_log와 동일한 목적).
 */
public interface WebSearchExecutionLogRepository extends JpaRepository<WebSearchExecutionLog, Long> {

    List<WebSearchExecutionLog> findByUserEmailOrderByCreatedAtDesc(String userEmail);

    List<WebSearchExecutionLog> findByExecutionStatusAndCreatedAtAfter(String status, LocalDateTime after);

    List<WebSearchExecutionLog> findByFailureCategoryAndCreatedAtAfter(String failureCategory, LocalDateTime after);

    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);
}
