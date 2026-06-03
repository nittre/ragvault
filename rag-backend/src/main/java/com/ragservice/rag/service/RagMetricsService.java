package com.ragservice.rag.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * RAG 시스템 커스텀 Micrometer 메트릭.
 *
 * 메트릭 목록:
 * - rag.query.total{intent=RAG|SQL|HYBRID|URL_FETCH|FILE|IMAGE}  — 경로별 요청 수
 * - rag.query.errors{intent=...}                                  — 경로별 에러 수
 * - rag.query.blocked{reason=injection|pii|sql_denied}           — 차단 수
 * - rag.binlog.lag.seconds                                        — binlog 처리 지연
 * - rag.guardrail.triggered{guard=A|B}                           — Guard A/B 발동 수
 * - rag.pii.masked{path=...}                                      — PII 마스킹 건수
 * - rag.query.duration{intent=...}                               — 경로별 응답 시간
 *
 * requirements/06-error-handling.md 섹션 12 참조.
 */
@Slf4j
@Service
public class RagMetricsService {

    private final MeterRegistry registry;
    private final Map<String, Counter> queryCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> errorCounters  = new ConcurrentHashMap<>();
    private final Map<String, Timer>   queryTimers    = new ConcurrentHashMap<>();

    public RagMetricsService(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 경로별 요청 카운터 증가 */
    public void incrementQuery(String intent) {
        queryCounters.computeIfAbsent(intent, k ->
            Counter.builder("rag.query.total")
                   .tag("intent", k)
                   .description("RAG 경로별 요청 수")
                   .register(registry)
        ).increment();
    }

    /** 경로별 에러 카운터 증가 */
    public void incrementError(String intent) {
        errorCounters.computeIfAbsent(intent, k ->
            Counter.builder("rag.query.errors")
                   .tag("intent", k)
                   .description("RAG 경로별 에러 수")
                   .register(registry)
        ).increment();
    }

    /** 차단 이벤트 기록 */
    public void incrementBlocked(String reason) {
        Counter.builder("rag.query.blocked")
               .tag("reason", reason)
               .description("보안 가드레일 차단 수")
               .register(registry)
               .increment();
    }

    /** Guard A/B 발동 기록 */
    public void incrementGuardrail(String guard) {
        Counter.builder("rag.guardrail.triggered")
               .tag("guard", guard)
               .description("파라미터 Guard A/B 발동 수")
               .register(registry)
               .increment();
    }

    /** PII 마스킹 건수 */
    public void incrementPiiMasked(String path) {
        Counter.builder("rag.pii.masked")
               .tag("path", path)
               .description("PII 마스킹 적용 건수")
               .register(registry)
               .increment();
    }

    /** binlog 지연 시간 (초) 기록 */
    public void recordBinlogLag(long lagSeconds) {
        registry.gauge("rag.binlog.lag.seconds", lagSeconds);
    }

    /** 응답 시간 기록 */
    public void recordQueryDuration(String intent, long durationMs) {
        queryTimers.computeIfAbsent(intent, k ->
            Timer.builder("rag.query.duration")
                 .tag("intent", k)
                 .description("RAG 경로별 응답 시간")
                 .register(registry)
        ).record(durationMs, TimeUnit.MILLISECONDS);
    }
}
