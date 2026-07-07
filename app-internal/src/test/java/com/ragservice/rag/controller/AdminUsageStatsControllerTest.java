package com.ragservice.rag.controller;

import com.ragservice.rag.domain.AuditLog;
import com.ragservice.rag.repository.AuditLogRepository;
import com.ragvault.core.repository.SqlExecutionLogRepository;
import com.ragvault.core.repository.WebSearchExecutionLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * AdminUsageStatsController 단위 테스트.
 *
 * routing(audit_log 기반)은 요청 라우팅 분류라 합계가 totalQueries와 일치해야 하고,
 * executions(sql_execution_log/web_search_execution_log 기반)는 HYBRID 내부 실행까지
 * 포함하는 별도 지표라 routing과 합계가 달라도 된다는 것을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminUsageStatsControllerTest {

    @Mock AuditLogRepository auditLogRepository;
    @Mock SqlExecutionLogRepository sqlExecutionLogRepository;
    @Mock WebSearchExecutionLogRepository webSearchExecutionLogRepository;

    @InjectMocks
    AdminUsageStatsController controller;

    private Page<AuditLog> pageOf(long total) {
        return new PageImpl<>(List.of(), Pageable.unpaged(), total);
    }

    @Test
    void daily_routingSumsToTotal_executionsAreIndependentMetric() {
        when(auditLogRepository.findFiltered(isNull(), isNull(), any(), any(), any()))
                .thenReturn(pageOf(20));
        when(auditLogRepository.findFiltered(isNull(), eq("RAG"), any(), any(), any()))
                .thenReturn(pageOf(5));
        when(auditLogRepository.findFiltered(isNull(), eq("SQL_QUERY"), any(), any(), any()))
                .thenReturn(pageOf(3));
        when(auditLogRepository.findFiltered(isNull(), eq("FILE_UPLOAD"), any(), any(), any()))
                .thenReturn(pageOf(1));
        when(auditLogRepository.findFiltered(isNull(), eq("HYBRID"), any(), any(), any()))
                .thenReturn(pageOf(10));
        when(auditLogRepository.findFiltered(isNull(), eq("WEB_SEARCH"), any(), any(), any()))
                .thenReturn(pageOf(1));
        when(auditLogRepository.findFiltered(isNull(), eq("OTHER"), any(), any(), any()))
                .thenReturn(pageOf(0));

        // HYBRID로 라우팅된 10건 안에서 SQL이 추가로 실행돼 routing.SQL_QUERY(3)보다 큰 값이 나와야 한다.
        when(sqlExecutionLogRepository.countByValidationResultAndCreatedAtBetween(eq("allowed"), any(), any()))
                .thenReturn(13L);
        when(webSearchExecutionLogRepository.countByCreatedAtBetween(any(), any()))
                .thenReturn(9L);

        ResponseEntity<?> response = controller.daily(null);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("totalQueries")).isEqualTo(20L);

        @SuppressWarnings("unchecked")
        Map<String, Object> routing = (Map<String, Object>) body.get("routing");
        long routingSum = routing.values().stream().mapToLong(v -> (long) v).sum();
        assertThat(routingSum).isEqualTo(20L);
        assertThat(routing.get("HYBRID")).isEqualTo(10L);
        assertThat(routing.get("WEB_SEARCH")).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        Map<String, Object> executions = (Map<String, Object>) body.get("executions");
        assertThat(executions.get("sqlQuery")).isEqualTo(13L);
        assertThat(executions.get("webSearch")).isEqualTo(9L);
        assertThat((long) executions.get("sqlQuery")).isGreaterThan((long) routing.get("SQL_QUERY"));
    }
}
