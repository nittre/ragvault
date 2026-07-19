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



import com.ragvault.core.domain.BinlogPosition;
import com.ragvault.core.domain.DataSourceConfig;
import com.ragvault.core.domain.RagTableConfig;
import com.ragvault.core.domain.SyncJob;
import com.ragvault.core.repository.BinlogPositionRepository;
import com.ragvault.core.repository.RagTableConfigRepository;
import com.ragvault.core.repository.SyncJobRepository;
import com.ragvault.core.service.DiscordNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 초기 전체 동기화 서비스.
 *
 * 처리 흐름:
 * 1. 현재 MySQL GTID 기록 (스냅샷 직전)
 * 2. 대상 테이블 SELECT * (streaming, fetchSize=MIN_VALUE)
 * 3. 8스레드 병렬 처리
 * 4. binlog_position에 시작 GTID 저장
 *
 * @Async: 관리자 API 호출 시 비동기로 실행 (즉시 반환).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InitialSyncService {

    /** 테이블명 allowlist 패턴 — SQL 인젝션 방어 (ADR-0007 Layer 1 보완). */
    private static final Pattern SAFE_TABLE_NAME =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");

    private final DataSourceConfigService dataSourceConfigService;
    private final RagTableConfigService ragTableConfigService;
    private final RagTableConfigRepository ragTableConfigRepository;
    private final RagColumnSuggestionService ragColumnSuggestionService;
    private final SchemaInspectorService schemaInspector;
    private final ChunkingService chunkingService;
    private final BinlogPositionRepository positionRepository;
    private final SyncJobRepository syncJobRepository;
    private final DiscordNotifier discordNotifier;

    private static final int THREAD_COUNT = 8;
    private static final int BATCH_LIMIT = 100;

    /** 동시 재임베딩 작업 수 제한 — LLM·JDBC 리소스 보호 */
    private static final java.util.concurrent.Semaphore RESYNC_SEMAPHORE =
            new java.util.concurrent.Semaphore(3);

    /**
     * LLM 컬럼 재분석 → 설정 업데이트 → 전체 재임베딩을 순차 실행 (단일 @Async 스레드).
     *
     * 스키마 변경(RENAME COLUMN 등) 감지 후 "재동기화" 클릭 시 호출.
     * self-invocation으로 runInitialSync를 호출하므로 LLM 완료 후 임베딩이 보장된다.
     */
    @Async
    public void resyncWithColumnUpdateAsync(Integer dsId, String tableName, String triggeredBy) {
        log.info("Resync with column update start: dsId={}, table={}", dsId, tableName);
        try {
            RESYNC_SEMAPHORE.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Resync interrupted while waiting for semaphore: dsId={}, table={}", dsId, tableName);
            return;
        }
        try {
            ragTableConfigRepository.findBySourceTableAndDatasourceId(tableName, dsId).ifPresent(c -> {
                c.setLlmStatus("pending");
                ragTableConfigRepository.save(c);
            });

            try {
                List<SchemaInspectorService.TableInfo> schemas = schemaInspector.getAllTablesWithSchema(dsId)
                        .stream().filter(t -> t.tableName().equals(tableName)).toList();

                if (!schemas.isEmpty()) {
                    Map<String, RagColumnSuggestionService.ColumnSuggestion> suggestions =
                            ragColumnSuggestionService.suggestAll(schemas);

                    ragTableConfigRepository.findBySourceTableAndDatasourceId(tableName, dsId).ifPresent(config -> {
                        RagColumnSuggestionService.ColumnSuggestion s = suggestions.get(tableName);
                        if (s != null) {
                            config.setTitleColumn(s.titleColumn());
                            config.setContentColumnsJson(String.join(",", s.contentColumns()));
                            config.setMetadataColumnsJson(String.join(",", s.metadataColumns()));
                            config.setChunkingStrategy(s.chunkingStrategy());
                            config.setChunkSize(s.chunkSize());
                            config.setChunkOverlap(s.chunkOverlap());
                            log.info("Column config updated via LLM: table={}, dsId={}, content={}",
                                    tableName, dsId, config.getContentColumnsJson());
                        }
                        config.setLlmStatus("done");
                        ragTableConfigRepository.save(config);
                        ragTableConfigService.refreshCache();
                    });
                }

                // self-invocation: @Async 프록시 미경유 → 현재 스레드에서 동기 실행 (LLM 완료 후 임베딩 보장)
                runInitialSync(dsId, List.of(tableName), triggeredBy);

            } catch (Exception e) {
                log.error("Resync with column update failed: dsId={}, table={}, error={}", dsId, tableName, e.getMessage(), e);
                ragTableConfigRepository.findBySourceTableAndDatasourceId(tableName, dsId).ifPresent(c -> {
                    c.setLlmStatus("done");
                    ragTableConfigRepository.save(c);
                });
            }
        } finally {
            RESYNC_SEMAPHORE.release();
        }
    }

    /**
     * 초기 동기화 (비동기 실행).
     *
     * @param datasourceId 동기화할 datasource ID (필수)
     * @param tables      동기화할 테이블 목록 (null이면 해당 datasource의 전체 활성 테이블)
     * @param triggeredBy 트리거한 사용자 이메일
     */
    @Async
    public CompletableFuture<SyncJob> runInitialSync(Integer datasourceId, List<String> tables,
                                                      String triggeredBy) {
        DataSourceConfig ds = dataSourceConfigService.findById(datasourceId);

        SyncJob job = syncJobRepository.save(SyncJob.builder()
                .triggerType("initial")
                .triggeredBy(triggeredBy)
                .status("running")
                .build());

        try (Connection conn = getCustomerConnection(ds)) {
            // 1. 현재 binlog 위치 기록 (스냅샷 직전 — 이후 binlog는 incremental sync가 처리)
            // MariaDB: SHOW MASTER STATUS → "file:position" 형식 저장
            // MySQL: SELECT @@global.gtid_executed → UUID:범위 형식 저장
            String gtidSet = "";
            boolean isMariaDb = "mariadb".equalsIgnoreCase(ds.getDbType());
            if (isMariaDb) {
                try (ResultSet rs = conn.createStatement().executeQuery("SHOW MASTER STATUS")) {
                    if (rs.next()) {
                        gtidSet = rs.getString("File") + ":" + rs.getLong("Position");
                    }
                } catch (SQLException e) {
                    log.warn("Could not retrieve MariaDB binlog position: {}", e.getMessage());
                }
            } else {
                try (ResultSet rs = conn.createStatement().executeQuery("SELECT @@global.gtid_executed")) {
                    if (rs.next()) gtidSet = rs.getString(1);
                } catch (SQLException e) {
                    log.warn("Could not retrieve GTID: {}", e.getMessage());
                }
            }

            // 2. 대상 테이블 결정
            List<RagTableConfig> targets;
            if (tables == null || tables.isEmpty()) {
                targets = ragTableConfigService.findAllActive().stream()
                        .filter(t -> java.util.Objects.equals(t.getDatasourceId(), datasourceId))
                        .toList();
            } else {
                targets = tables.stream()
                        .map(t -> ragTableConfigService.findByTable(datasourceId, t))
                        .filter(java.util.Optional::isPresent)
                        .map(java.util.Optional::get)
                        .filter(t -> java.util.Objects.equals(t.getDatasourceId(), datasourceId))
                        .toList();
            }

            int totalSuccess = 0, totalFailed = 0;

            // 3. 테이블별 처리
            for (RagTableConfig config : targets) {
                log.info("Initial sync: table={}, datasourceId={}", config.getSourceTable(), datasourceId);
                int[] counts = processTable(conn, config);
                totalSuccess += counts[0];
                totalFailed += counts[1];
                discordNotifier.info("초기 동기화 진행: " + config.getSourceTable() +
                        " 완료 (성공: " + counts[0] + ", 실패: " + counts[1] + ")");
            }

            // 4. GTID 위치 저장 (datasource_id 기준)
            BinlogPosition pos = positionRepository.findByDatasourceId(datasourceId)
                    .orElseGet(() -> {
                        BinlogPosition p = new BinlogPosition();
                        p.setDatasourceId(datasourceId);
                        return p;
                    });
            pos.setGtidSet(gtidSet);
            pos.setLastEventAt(Instant.now());
            pos.setUpdatedAt(Instant.now());
            positionRepository.save(pos);

            job.setStatus(totalFailed > 0 ? "partial" : "success");
            job.setRecordsSuccess(totalSuccess);
            job.setRecordsFailed(totalFailed);
            job.setCompletedAt(Instant.now());
            syncJobRepository.save(job);

            discordNotifier.info("초기 동기화 완료: 성공=" + totalSuccess + ", 실패=" + totalFailed);

        } catch (Exception e) {
            log.error("Initial sync error: {}", e.getMessage(), e);
            job.setStatus("failed");
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(Instant.now());
            syncJobRepository.save(job);
            discordNotifier.critical("초기 동기화 실패: " + e.getMessage());
        }

        return CompletableFuture.completedFuture(job);
    }

    /**
     * 테이블 전체 행 처리 (streaming + 병렬).
     *
     * fetchSize=Integer.MIN_VALUE: MySQL JDBC streaming mode
     * (메모리에 전체 결과셋을 올리지 않음).
     */
    private int[] processTable(Connection conn, RagTableConfig config) throws SQLException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        String tableName = config.getSourceTable();
        if (!SAFE_TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException(
                    "Invalid table name (SQL injection guard): " + tableName);
        }
        String sql = "SELECT * FROM `" + tableName + "`";
        // MySQL: Integer.MIN_VALUE → row-by-row streaming
        // MariaDB Connector/J: Integer.MIN_VALUE 미지원 → 배치 fetch 사용
        String dbUrl = conn.getMetaData().getURL();
        int fetchSize = (dbUrl != null && dbUrl.contains("mariadb")) ? 200 : Integer.MIN_VALUE;
        try (PreparedStatement ps = conn.prepareStatement(sql,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            ps.setFetchSize(fetchSize);
            ResultSet rs = ps.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            List<Future<?>> futures = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(meta.getColumnName(i), rs.getObject(i));
                }
                final Map<String, Object> rowCopy = new LinkedHashMap<>(row);

                futures.add(executor.submit(() -> {
                    try {
                        chunkingService.processRow(config, rowCopy);
                        success.incrementAndGet();
                    } catch (Exception e) {
                        log.error("Row failed: table={}: {}", config.getSourceTable(), e.getMessage());
                        failed.incrementAndGet();
                    }
                }));

                // 배치 크기 제한 (메모리 관리)
                if (futures.size() >= BATCH_LIMIT) {
                    drainFutures(futures);
                }
            }
            drainFutures(futures);
        } finally {
            executor.shutdown();
        }
        return new int[]{success.get(), failed.get()};
    }

    private void drainFutures(List<Future<?>> futures) {
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {}
        }
        futures.clear();
    }

    private Connection getCustomerConnection(DataSourceConfig ds) throws SQLException {
        String url = dataSourceConfigService.buildJdbcUrl(ds);
        String password = dataSourceConfigService.getDecryptedPassword(ds);
        return DriverManager.getConnection(url, ds.getUsername(), password);
    }
}
