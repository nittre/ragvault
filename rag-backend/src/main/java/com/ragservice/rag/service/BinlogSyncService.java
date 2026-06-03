package com.ragservice.rag.service;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.*;
import com.ragservice.rag.domain.*;
import com.ragservice.rag.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MySQL binlog 기반 증분 동기화 서비스.
 *
 * ADR-0001: 30분 주기 GTID 기반 동기화.
 * ShedLock으로 분산 환경에서 단일 인스턴스만 실행.
 *
 * 처리 흐름:
 * 1. binlog_position에서 마지막 GTID 로드
 * 2. BinaryLogClient로 MySQL binlog 연결
 * 3. 이벤트 수신 후 RAG 대상 테이블만 처리
 * 4. GTID 위치 업데이트
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BinlogSyncService {

    @Value("${rag.mysql.host}") private String mysqlHost;
    @Value("${rag.mysql.port:3306}") private int mysqlPort;
    @Value("${rag.mysql.database}") private String mysqlDatabase;
    @Value("${rag.mysql.username}") private String mysqlUsername;
    @Value("${rag.mysql.password}") private String mysqlPassword;

    private final BinlogPositionRepository positionRepository;
    private final BinlogEventRepository binlogEventRepository;
    private final DdlEventRepository ddlEventRepository;
    private final SyncJobRepository syncJobRepository;
    private final RagTableConfigService ragTableConfigService;
    private final ChunkingService chunkingService;
    private final DiscordNotifier discordNotifier;

    /** 이벤트 없을 때 연결 유지 시간 (30초) */
    private static final long CONNECT_TIMEOUT_MS = 30_000L;

    /**
     * 30분 주기 배치 동기화.
     * ShedLock으로 분산 환경에서 한 인스턴스만 실행.
     */
    @Scheduled(cron = "0 */30 * * * *")
    @SchedulerLock(name = "binlogSync", lockAtMostFor = "20m", lockAtLeastFor = "1m")
    public void scheduledSync() {
        log.info("Scheduled binlog sync started");
        syncSinceLastGtid("scheduled", null);
    }

    /**
     * 수동 트리거 (관리자 API).
     */
    public SyncJob triggerManualSync(String triggeredBy) {
        return syncSinceLastGtid("manual", triggeredBy);
    }

    @Transactional
    public SyncJob syncSinceLastGtid(String triggerType, String triggeredBy) {
        SyncJob job = syncJobRepository.save(SyncJob.builder()
                .triggerType(triggerType)
                .triggeredBy(triggeredBy)
                .status("running")
                .build());

        BinlogPosition pos = positionRepository.findById(1).orElseGet(() -> {
            BinlogPosition p = new BinlogPosition();
            p.setId(1);
            p.setGtidSet("");
            return positionRepository.save(p);
        });

        // lag 체크: lastEventAt이 1시간 이상 오래됐으면 Warning
        if (pos.getLastEventAt() != null) {
            long lagMin = java.time.Duration.between(pos.getLastEventAt(), Instant.now()).toMinutes();
            if (lagMin > 60) {
                discordNotifier.warning("binlog lag " + lagMin + "분 초과. 동기화 지연 가능성 있음.");
            }
        }

        int[] counts = {0, 0};  // [success, failed]

        try {
            BinaryLogClient client = new BinaryLogClient(
                    mysqlHost, mysqlPort, mysqlDatabase, mysqlUsername, mysqlPassword);

            if (!pos.getGtidSet().isBlank()) {
                client.setGtidSet(pos.getGtidSet());
            }

            // 테이블 ID → TableMapEventData 매핑
            Map<Long, TableMapEventData> tableMap = new ConcurrentHashMap<>();
            String[] currentGtid = {pos.getGtidSet()};
            Instant[] lastEventAt = {pos.getLastEventAt()};

            client.registerEventListener(event -> {
                Object data = event.getData();

                if (data instanceof GtidEventData gtid) {
                    currentGtid[0] = gtid.getGtid();
                    lastEventAt[0] = Instant.now();

                } else if (data instanceof TableMapEventData tme) {
                    tableMap.put(tme.getTableId(), tme);

                } else if (data instanceof WriteRowsEventData wr) {
                    processDataEvent("INSERT", tableMap.get(wr.getTableId()), wr.getRows(), counts);

                } else if (data instanceof UpdateRowsEventData ur) {
                    processUpdateEvent(tableMap.get(ur.getTableId()), ur.getRows(), counts);

                } else if (data instanceof DeleteRowsEventData dr) {
                    processDataEvent("DELETE", tableMap.get(dr.getTableId()), dr.getRows(), counts);

                } else if (data instanceof QueryEventData qe) {
                    processDdlEvent(qe.getSql());
                }
            });

            client.setKeepAlive(false);
            client.connect(CONNECT_TIMEOUT_MS);
            Thread.sleep(CONNECT_TIMEOUT_MS);
            client.disconnect();

            // GTID 위치 업데이트
            pos.setGtidSet(currentGtid[0]);
            pos.setLastEventAt(lastEventAt[0] != null ? lastEventAt[0] : Instant.now());
            pos.setUpdatedAt(Instant.now());
            positionRepository.save(pos);

            job.setStatus(counts[1] > 0 ? "partial" : "success");
            job.setRecordsSuccess(counts[0]);
            job.setRecordsFailed(counts[1]);
            job.setCompletedAt(Instant.now());
            syncJobRepository.save(job);

            log.info("Binlog sync completed: success={}, failed={}", counts[0], counts[1]);

        } catch (Exception e) {
            log.error("Binlog sync error: {}", e.getMessage(), e);
            job.setStatus("failed");
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(Instant.now());
            syncJobRepository.save(job);
            discordNotifier.warning("binlog 동기화 실패: " + e.getMessage());
        }

        return job;
    }

    private void processDataEvent(String opType,
            TableMapEventData tme,
            List<?> rows,
            int[] counts) {
        if (tme == null) return;
        String tableName = tme.getTable();

        Optional<RagTableConfig> configOpt = ragTableConfigService.findByTable(tableName);
        if (configOpt.isEmpty()) return;

        RagTableConfig config = configOpt.get();

        for (Object rowObj : rows) {
            java.io.Serializable[] row = (java.io.Serializable[]) rowObj;
            try {
                Map<String, Object> rowMap = buildRowMap(tme, row);
                String sourceId = String.valueOf(rowMap.get(config.getPkColumn()));

                if ("DELETE".equals(opType)) {
                    chunkingService.deleteChunks(tableName, sourceId);
                } else {
                    chunkingService.processRow(config, rowMap);
                }
                counts[0]++;
            } catch (Exception e) {
                counts[1]++;
                log.error("Row processing failed: table={}, op={}: {}", tableName, opType, e.getMessage());
                binlogEventRepository.save(BinlogEvent.builder()
                        .eventType(opType)
                        .tableName(tableName)
                        .attempt(3)
                        .errorMessage(e.getMessage())
                        .build());
            }
        }
    }

    private void processUpdateEvent(
            TableMapEventData tme,
            List<Map.Entry<java.io.Serializable[], java.io.Serializable[]>> rows,
            int[] counts) {
        if (tme == null) return;
        String tableName = tme.getTable();
        Optional<RagTableConfig> configOpt = ragTableConfigService.findByTable(tableName);
        if (configOpt.isEmpty()) return;

        RagTableConfig config = configOpt.get();
        for (Map.Entry<java.io.Serializable[], java.io.Serializable[]> entry : rows) {
            try {
                Map<String, Object> newRowMap = buildRowMap(tme, entry.getValue());
                String sourceId = String.valueOf(newRowMap.get(config.getPkColumn()));
                chunkingService.deleteChunks(tableName, sourceId);
                chunkingService.processRow(config, newRowMap);
                counts[0]++;
            } catch (Exception e) {
                counts[1]++;
                log.error("Update processing failed: table={}: {}", tableName, e.getMessage());
            }
        }
    }

    private void processDdlEvent(String sql) {
        if (sql == null || sql.isBlank()) return;
        String upperSql = sql.trim().toUpperCase();

        // DDL 여부 판별
        if (!upperSql.startsWith("CREATE") && !upperSql.startsWith("ALTER") &&
            !upperSql.startsWith("DROP") && !upperSql.startsWith("RENAME") &&
            !upperSql.startsWith("TRUNCATE")) return;

        String riskLevel = classifyDdlRisk(upperSql);
        String tableName = extractTableName(sql);

        DdlEvent event = DdlEvent.builder()
                .sqlQuery(sql)
                .tableName(tableName)
                .eventType(upperSql.split("\\s+")[0])
                .riskLevel(riskLevel)
                .autoApplyAt("MEDIUM".equals(riskLevel) ? Instant.now().plusSeconds(7 * 86400L) : null)
                .build();

        ddlEventRepository.save(event);

        switch (riskLevel) {
            case "LOW" -> discordNotifier.info("DDL 이벤트 (LOW): " + sql);
            case "MEDIUM" -> discordNotifier.warning("DDL 이벤트 (MEDIUM) - 7일 내 검토 필요: " + sql);
            case "HIGH" -> discordNotifier.critical("DDL 이벤트 (HIGH) - 즉시 검토 필요: " + sql);
        }
    }

    private String classifyDdlRisk(String upperSql) {
        if (upperSql.startsWith("DROP") || upperSql.startsWith("TRUNCATE") ||
            upperSql.startsWith("RENAME") || upperSql.contains("DROP COLUMN")) return "HIGH";
        if (upperSql.startsWith("ALTER") ||
            (upperSql.contains("ADD COLUMN") && upperSql.contains("NOT NULL"))) return "MEDIUM";
        return "LOW";
    }

    private String extractTableName(String sql) {
        try {
            String[] tokens = sql.trim().split("\\s+");
            for (int i = 0; i < tokens.length - 1; i++) {
                if ("TABLE".equalsIgnoreCase(tokens[i]) || "INTO".equalsIgnoreCase(tokens[i])) {
                    return tokens[i + 1].replaceAll("[`\"']", "").split("\\.")[0];
                }
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    /**
     * binlog row → 컬럼명 매핑.
     *
     * mysql-binlog-connector-java는 컬럼 타입만 제공하고 이름은 제공하지 않음.
     * InitialSyncService에서는 JDBC ResultSetMetaData로 정확한 이름 사용.
     * binlog 이벤트에서는 col_N 형태로 임시 저장.
     *
     * Phase 1+: INFORMATION_SCHEMA.COLUMNS 조회로 개선 예정.
     */
    private Map<String, Object> buildRowMap(TableMapEventData tme, java.io.Serializable[] row) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (int i = 0; i < row.length; i++) {
            result.put("col_" + i, row[i]);
        }
        return result;
    }

    /**
     * MEDIUM DDL 이벤트 7일 자동 처리 (매일 04:00).
     */
    @Scheduled(cron = "0 0 4 * * *")
    @SchedulerLock(name = "ddlAutoApply", lockAtMostFor = "5m")
    public void autoApplyMediumDdlEvents() {
        List<DdlEvent> ready = ddlEventRepository.findMediumEventsReadyForAutoApply(Instant.now());
        for (DdlEvent event : ready) {
            event.setProcessedAt(Instant.now());
            event.setProcessedBy("system-auto-timeout");
            event.setActionTaken("auto-applied-after-timeout");
            event.setNotes("7일간 관리자 무응답으로 시스템 자동 처리 (스키마 캐시 무효화만)");
            ddlEventRepository.save(event);
            log.info("Auto-applied MEDIUM DDL event: {}", event.getId());
        }
    }
}
