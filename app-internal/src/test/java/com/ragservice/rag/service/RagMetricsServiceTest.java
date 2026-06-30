package com.ragservice.rag.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RagMetricsService 단위 테스트.
 *
 * SimpleMeterRegistry를 사용해 외부 의존성 없이 메트릭 기록을 검증한다.
 */
class RagMetricsServiceTest {

    private MeterRegistry registry;
    private RagMetricsService metricsService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metricsService = new RagMetricsService(registry);
    }

    @Test
    void incrementQuery_registers_counter_with_intent_tag() {
        metricsService.incrementQuery("RAG");
        metricsService.incrementQuery("RAG");
        metricsService.incrementQuery("SQL");

        Counter ragCounter = registry.find("rag.query.total").tag("intent", "RAG").counter();
        Counter sqlCounter = registry.find("rag.query.total").tag("intent", "SQL").counter();

        assertThat(ragCounter).isNotNull();
        assertThat(ragCounter.count()).isEqualTo(2.0);
        assertThat(sqlCounter).isNotNull();
        assertThat(sqlCounter.count()).isEqualTo(1.0);
    }

    @Test
    void incrementError_registers_error_counter() {
        metricsService.incrementError("HYBRID");

        Counter counter = registry.find("rag.query.errors").tag("intent", "HYBRID").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void incrementBlocked_registers_blocked_counter_with_reason() {
        metricsService.incrementBlocked("injection");
        metricsService.incrementBlocked("injection");
        metricsService.incrementBlocked("sql_denied");

        Counter injectionCounter = registry.find("rag.query.blocked").tag("reason", "injection").counter();
        Counter sqlDeniedCounter = registry.find("rag.query.blocked").tag("reason", "sql_denied").counter();

        assertThat(injectionCounter).isNotNull();
        assertThat(injectionCounter.count()).isEqualTo(2.0);
        assertThat(sqlDeniedCounter).isNotNull();
        assertThat(sqlDeniedCounter.count()).isEqualTo(1.0);
    }

    @Test
    void incrementGuardrail_registers_guard_counter() {
        metricsService.incrementGuardrail("A");
        metricsService.incrementGuardrail("B");
        metricsService.incrementGuardrail("A");

        Counter guardA = registry.find("rag.guardrail.triggered").tag("guard", "A").counter();
        Counter guardB = registry.find("rag.guardrail.triggered").tag("guard", "B").counter();

        assertThat(guardA).isNotNull();
        assertThat(guardA.count()).isEqualTo(2.0);
        assertThat(guardB).isNotNull();
        assertThat(guardB.count()).isEqualTo(1.0);
    }

    @Test
    void incrementPiiMasked_registers_pii_counter_with_path() {
        metricsService.incrementPiiMasked("RAG");
        metricsService.incrementPiiMasked("SQL");

        Counter ragPii = registry.find("rag.pii.masked").tag("path", "RAG").counter();
        assertThat(ragPii).isNotNull();
        assertThat(ragPii.count()).isEqualTo(1.0);
    }

    @Test
    void recordBinlogLag_registers_gauge() {
        metricsService.recordBinlogLag(120L);

        Double gaugeValue = registry.find("rag.binlog.lag.seconds").gauge().value();
        assertThat(gaugeValue).isEqualTo(120.0);
    }

    @Test
    void recordQueryDuration_registers_timer() {
        metricsService.recordQueryDuration("IMAGE", 500L);

        var timer = registry.find("rag.query.duration").tag("intent", "IMAGE").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
    }
}
