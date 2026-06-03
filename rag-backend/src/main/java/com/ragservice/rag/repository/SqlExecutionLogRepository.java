package com.ragservice.rag.repository;

import com.ragservice.rag.domain.SqlExecutionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * sql_execution_log 리포지토리.
 * SQL 실행 감사 로그 조회 (ADR-0007, ADR-0010).
 */
public interface SqlExecutionLogRepository extends JpaRepository<SqlExecutionLog, Long> {

    List<SqlExecutionLog> findByUserEmailOrderByCreatedAtDesc(String userEmail);

    List<SqlExecutionLog> findByExecutionStatusAndCreatedAtAfter(String status, LocalDateTime after);
}
