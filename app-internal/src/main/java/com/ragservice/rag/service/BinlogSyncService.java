package com.ragservice.rag.service;

import com.ragvault.core.service.SchemaInspectorService;
import com.ragvault.core.service.DataSourceConfigService;
import com.ragvault.core.service.ChunkingService;
import com.ragvault.core.service.RagTableConfigService;
import com.ragvault.core.service.SensitivityAnalysisService;
import com.ragvault.core.service.RagColumnSuggestionService;
import com.ragvault.core.service.SchemaDescriptionService;
import com.ragvault.core.service.SqlGeneratorService;
import com.ragvault.core.service.DataSourceRouterService;
import com.ragvault.core.service.RoutingEmbeddingService;
import com.ragvault.core.service.QueryIntent;



import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.*;
import com.ragservice.rag.domain.*;
import com.ragservice.rag.repository.*;
import com.ragvault.core.domain.*;
import com.ragvault.core.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MySQL/MariaDB binlog 기반 증분 동기화 서비스.
 *
 * ADR-0001: 30분 주기 GTID 기반 동기화.
 * ShedLock으로 분산 환경에서 단일 인스턴스만 실행.
 *
 * 멀티 데이터소스:
 * - scheduledSync() 에서 모든 활성 datasource 순회
 * - 연결 정보는 datasource_config 테이블에서만 조회 (환경변수 fallback 없음)
 * - BinlogPosition: datasource_id 별로 관리
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

    private final BinlogPositionRepository positionRepository;
    private final BinlogEventRepository binlogEventRepository;
    private final DdlEventRepository ddlEventRepository;
    private final SyncJobRepository syncJobRepository;
    private final RagTableConfigService ragTableConfigService;
    private final ChunkingService chunkingService;
    private final DiscordNotifier discordNotifier;
    private final DataSourceConfigService dataSourceConfigService;
    private final WhitelistSyncService whitelistSyncService;

    private final ConcurrentHashMap<Integer, Boolean> syncInProgress = new ConcurrentHashMap<>();

    /** 이벤트 없을 때 연결 유지 시간 (30초) */
    private static final long CONNECT_TIMEOUT_MS = 30_000L;

    /** 이 시간 동안 이벤트 없으면 따라잡았다고 판단 (2초) */
    private static final long CATCHUP_IDLE_MS = 2_000L;

    /**
     * 30분 주기 배치 동기화.
     * datasource_config 의 모든 활성 datasource 를 순회하며 동기화.
     */
    @Scheduled(cron = "0 */30 * * * *")
    @SchedulerLock(name = "binlogSync", lockAtMostFor = "20m", lockAtLeastFor = "1m")
    public void scheduledSync() {
        log.info("Scheduled binlog sync started");

        List<DataSourceConfig> activeDataSources = dataSourceConfigService.findActiveAll();
        for (DataSourceConfig ds : activeDataSources) {
            try {
                syncSinceLastGtid("scheduled", null, ds);
            } catch (Exception e) {
                log.error("Scheduled sync failed for datasource id={}, name={}: {}",
                        ds.getId(), ds.getName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 수동 트리거 — 특정 datasource.
     */
    public SyncJob triggerManualSync(String triggeredBy, DataSourceConfig datasource) {
        return syncSinceLastGtid("manual", triggeredBy, datasource);
    }

    /**
     * 비동기 binlog 동기화 + 화이트리스트 재적용.
     * OFF→ON 전환 시 HTTP 스레드를 블로킹하지 않고 백그라운드에서 실행.
     *
     * @param since disabledAt 시점 (null이면 재적용 없이 binlog 스캔만)
     */
    @org.springframework.scheduling.annotation.Async
    public void triggerSyncAndReplayAsync(String triggeredBy, DataSourceConfig datasource,
                                           Integer dsId, String tableType,
                                           java.time.Instant since) {
        log.info("[SyncMode] async binlog catch-up start: dsId={}", dsId);
        syncInProgress.put(dsId, true);
        try {
            syncSinceLastGtid("manual", triggeredBy, datasource);
            if (since != null) {
                try {
                    whitelistSyncService.replaySince(dsId, tableType, since);
                    log.info("[SyncMode] async replay done: dsId={}", dsId);
                } catch (Exception e) {
                    log.warn("[SyncMode] async replay failed: dsId={}, error={}", dsId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[SyncMode] async binlog catch-up failed: dsId={}, error={}", dsId, e.getMessage());
        } finally {
            syncInProgress.put(dsId, false);
        }
    }

    public boolean isSyncInProgress(Integer dsId) {
        return Boolean.TRUE.equals(syncInProgress.get(dsId));
    }

    /**
     * 핵심 동기화 메서드.
     *
     * @param triggerType  "scheduled" | "manual"
     * @param triggeredBy  사용자 이메일 (수동 시) 또는 null
     * @param datasource   datasource_config 엔티티 (필수)
     */
    public SyncJob syncSinceLastGtid(String triggerType, String triggeredBy,
                                      DataSourceConfig datasource) {
        SyncJob job = syncJobRepository.save(SyncJob.builder()
                .triggerType(triggerType)
                .triggeredBy(triggeredBy)
                .status("running")
                .build());

        // 연결 정보 결정
        String host = datasource.getHost();
        int port = datasource.getPort();
        String database = datasource.getDbName();
        String username = datasource.getUsername();
        String password = dataSourceConfigService.getDecryptedPassword(datasource);

        // BinlogPosition 조회 (datasource_id 별)
        final Integer dsId = datasource.getId();
        BinlogPosition pos = positionRepository.findByDatasourceId(dsId).orElseGet(() -> {
            BinlogPosition p = new BinlogPosition();
            p.setGtidSet("");
            p.setDatasourceId(dsId);
            return positionRepository.save(p);
        });

        // lag 체크: lastEventAt이 1시간 이상 오래됐으면 Warning
        if (pos.getLastEventAt() != null) {
            long lagMin = java.time.Duration.between(pos.getLastEventAt(), Instant.now()).toMinutes();
            if (lagMin > 60) {
                discordNotifier.warning("binlog lag " + lagMin + "분 초과 (ds=" + datasource.getId() + "). 동기화 지연 가능성 있음.");
            }
        }

        int[] counts = {0, 0};  // [success, failed]
        boolean isMariaDb = "mariadb".equalsIgnoreCase(datasource.getDbType());

        try {
            if (isMariaDb) {
                // MariaDB: SHOW BINLOG EVENTS via JDBC (BinaryLogClient는 MySQL GTID 전용)
                syncMariaDbBinlogViaJdbc(dsId, datasource, pos, counts);
            } else {
                // MySQL: BinaryLogClient
                BinaryLogClient client = new BinaryLogClient(host, port, database, username, password);

                if (!pos.getGtidSet().isBlank()) {
                    // 이전에 처리한 GTID가 있으면 그 다음부터 읽기
                    client.setGtidSet(pos.getGtidSet());
                } else {
                    // gtid_set 미설정: 가장 오래된 binlog 파일 처음부터 읽어 누락된 DDL 보충
                    // (BinaryLogClient에 아무것도 설정 안 하면 현재 말단부터 읽어 과거 이벤트를 놓침)
                    try {
                        String jdbcUrl = dataSourceConfigService.buildJdbcUrl(datasource);
                        String pw = dataSourceConfigService.getDecryptedPassword(datasource);
                        try (Connection initConn = DriverManager.getConnection(jdbcUrl, datasource.getUsername(), pw);
                             java.sql.Statement stmt = initConn.createStatement();
                             java.sql.ResultSet rs = stmt.executeQuery("SHOW BINARY LOGS")) {
                            if (rs.next()) {
                                client.setBinlogFilename(rs.getString("Log_name"));
                                client.setBinlogPosition(4L);
                                log.info("[BinlogSync] gtid_set empty — starting from beginning: file={}", rs.getString("Log_name"));
                            }
                        }
                    } catch (Exception initEx) {
                        log.warn("[BinlogSync] Could not resolve earliest binlog for dsId={}: {}", dsId, initEx.getMessage());
                    }
                }

                Map<Long, TableMapEventData> tableMap = new ConcurrentHashMap<>();
                String[] currentGtid = {pos.getGtidSet()};
                Instant[] lastEventAt = {pos.getLastEventAt()};
                java.util.concurrent.atomic.AtomicLong lastEventNs = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

                client.registerEventListener(event -> {
                    lastEventNs.set(System.nanoTime());
                    Object data = event.getData();

                    if (data instanceof GtidEventData gtid) {
                        String raw = gtid.getGtid();
                        int colon = raw.lastIndexOf(':');
                        if (colon > 0) {
                            currentGtid[0] = raw.substring(0, colon) + ":1-" + raw.substring(colon + 1);
                        } else {
                            currentGtid[0] = raw;
                        }
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
                        processDdlEvent(dsId, qe.getSql());
                    }
                });

                client.setKeepAlive(false);
                client.connect(CONNECT_TIMEOUT_MS);
                // 이벤트가 CATCHUP_IDLE_MS 동안 오지 않으면 따라잡았다고 판단 (max CONNECT_TIMEOUT_MS)
                long deadlineNs = System.nanoTime() + CONNECT_TIMEOUT_MS * 1_000_000L;
                while (System.nanoTime() < deadlineNs) {
                    if (System.nanoTime() - lastEventNs.get() >= CATCHUP_IDLE_MS * 1_000_000L) break;
                    Thread.sleep(100);
                }
                client.disconnect();

                pos.setGtidSet(currentGtid[0]);
                pos.setLastEventAt(lastEventAt[0] != null ? lastEventAt[0] : Instant.now());
                pos.setUpdatedAt(Instant.now());
                positionRepository.save(pos);
            }

            job.setStatus(counts[1] > 0 ? "partial" : "success");
            job.setRecordsSuccess(counts[0]);
            job.setRecordsFailed(counts[1]);
            job.setCompletedAt(Instant.now());
            syncJobRepository.save(job);

            log.info("Binlog sync completed: datasourceId={}, success={}, failed={}",
                    datasource.getId(), counts[0], counts[1]);

        } catch (Exception e) {
            log.error("Binlog sync error: datasourceId={}, error={}",
                    datasource.getId(), e.getMessage(), e);
            job.setStatus("failed");
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(Instant.now());
            syncJobRepository.save(job);
            discordNotifier.warning("binlog 동기화 실패 (ds=" + datasource.getId() + "): " + e.getMessage());
        }

        return job;
    }

    /**
     * MariaDB binlog 이벤트를 JDBC SHOW BINLOG EVENTS로 읽어 처리.
     *
     * BinaryLogClient(mysql-binlog-connector-java)는 MySQL GTID 전용이라
     * MariaDB에서 GTID 없이 연결하면 항상 현재 binlog 끝에서 시작해
     * 기존 DDL 이벤트를 놓친다. JDBC 방식은 저장된 파일:위치부터 순차 읽기.
     *
     * gtid_set 저장 형식: "mariadb-bin.000001:1234" (파일명:위치)
     */
    private void syncMariaDbBinlogViaJdbc(Integer dsId, DataSourceConfig datasource,
                                           BinlogPosition pos, int[] counts) throws Exception {
        String jdbcUrl = dataSourceConfigService.buildJdbcUrl(datasource);
        String password = dataSourceConfigService.getDecryptedPassword(datasource);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, datasource.getUsername(), password)) {
            String[] fileAndPos = resolveMariaDbStartPosition(pos.getGtidSet(), conn);
            String currentFile = fileAndPos[0];
            long currentPos = Long.parseLong(fileAndPos[1]);
            String lastFile = currentFile;
            long lastPos = currentPos;
            Instant lastEventAt = pos.getLastEventAt();

            boolean hasMore = true;
            while (hasMore) {
                String query = "SHOW BINLOG EVENTS IN '" + currentFile + "' FROM " + currentPos + " LIMIT 1000";
                int eventCount = 0;

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(query)) {
                    while (rs.next()) {
                        eventCount++;
                        long endLogPos = rs.getLong("End_log_pos");
                        String eventType = rs.getString("Event_type");
                        String info = rs.getString("Info");

                        lastFile = currentFile;
                        lastPos = Math.max(lastPos, endLogPos);
                        lastEventAt = Instant.now();

                        if ("Query".equals(eventType) && info != null
                                && !"BEGIN".equals(info) && !"COMMIT".equals(info)) {
                            // MariaDB Query 이벤트는 "use `db`; ALTER TABLE ..." 형식으로 옴
                            // → 세미콜론 앞이 USE 문이면 그 이후만 추출
                            String sql = info.trim();
                            int semiIdx = sql.indexOf(';');
                            if (semiIdx >= 0 && sql.substring(0, semiIdx).trim().toUpperCase().startsWith("USE ")) {
                                sql = sql.substring(semiIdx + 1).trim();
                            }
                            if (!sql.isBlank()) {
                                processDdlEvent(dsId, sql);
                            }
                        }
                    }
                }

                if (eventCount >= 1000) {
                    // 현재 파일에 아직 이벤트가 남아 있을 수 있음 — 같은 파일을 계속 읽음
                    currentPos = lastPos;
                } else {
                    // 현재 파일을 모두 소진했으므로 다음 binlog 파일로 이동
                    String nextFile = findNextBinlogFile(conn, currentFile);
                    if (nextFile != null) {
                        log.debug("[BinlogSync] Advancing to next binlog file: {} → {}", currentFile, nextFile);
                        currentFile = nextFile;
                        currentPos = 4;
                    } else {
                        // 다음 파일 없음 — 모든 binlog 소진
                        hasMore = false;
                    }
                }
            }

            pos.setGtidSet(lastFile + ":" + lastPos);
            pos.setLastEventAt(lastEventAt != null ? lastEventAt : Instant.now());
            pos.setUpdatedAt(Instant.now());
            positionRepository.save(pos);
            log.info("MariaDB JDBC binlog sync done: dsId={}, pos={}:{}", dsId, lastFile, lastPos);
        }
    }

    /**
     * SHOW BINARY LOGS에서 currentFile 다음 binlog 파일명을 반환.
     * 다음 파일이 없으면 null 반환.
     */
    private String findNextBinlogFile(Connection conn, String currentFile) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW BINARY LOGS")) {
            while (rs.next()) {
                if (currentFile.equals(rs.getString("Log_name"))) {
                    if (rs.next()) {
                        return rs.getString("Log_name");
                    } else {
                        return null;  // currentFile이 마지막 파일
                    }
                }
            }
        }
        return null;  // currentFile을 목록에서 찾지 못한 경우 (purged 등)
    }

    /**
     * 저장된 gtid_set에서 MariaDB 시작 위치(파일명, 오프셋)를 결정.
     * 저장값이 "파일명:위치" 형식이면 그대로 사용, 비어있으면 첫 binlog 파일 위치 4부터 시작.
     */
    private String[] resolveMariaDbStartPosition(String gtidSet, Connection conn) throws Exception {
        if (gtidSet != null && !gtidSet.isBlank()) {
            int lastColon = gtidSet.lastIndexOf(':');
            if (lastColon > 0) {
                String afterColon = gtidSet.substring(lastColon + 1);
                if (afterColon.matches("\\d+")) {
                    return new String[]{gtidSet.substring(0, lastColon), afterColon};
                }
            }
        }
        // 첫 실행: SHOW BINARY LOGS에서 가장 오래된 파일 위치 4부터 시작
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW BINARY LOGS")) {
            if (rs.next()) {
                return new String[]{rs.getString("Log_name"), "4"};
            }
        }
        throw new Exception("MariaDB SHOW BINARY LOGS 결과 없음 — binlog가 활성화되지 않은 것 같습니다");
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

    private void processDdlEvent(Integer dsId, String sql) {
        if (sql == null || sql.isBlank()) return;
        String upperSql = sql.trim().toUpperCase();

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
                .datasourceId(dsId)
                .autoApplyAt("MEDIUM".equals(riskLevel) ? Instant.now().plusSeconds(7 * 86400L) : null)
                .build();

        DdlEvent saved = ddlEventRepository.save(event);

        switch (riskLevel) {
            case "LOW" -> discordNotifier.info("DDL 이벤트 (LOW): " + sql);
            case "MEDIUM" -> discordNotifier.warning("DDL 이벤트 (MEDIUM) - 7일 내 검토 필요: " + sql);
            case "HIGH" -> discordNotifier.critical("DDL 이벤트 (HIGH) - 즉시 검토 필요: " + sql);
        }

        // 화이트리스트 자동 동기화 (처리 후 whitelist_applied_*_at 기록)
        try {
            whitelistSyncService.onDdl(dsId, saved);
        } catch (Exception e) {
            log.warn("Whitelist sync failed for DDL: dsId={}, error={}", dsId, e.getMessage());
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
     * mysql-binlog-connector-java는 컬럼명을 제공하지 않으므로 col_N 형태 임시 저장.
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
