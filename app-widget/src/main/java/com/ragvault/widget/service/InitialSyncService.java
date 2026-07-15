package com.ragvault.widget.service;

import com.ragvault.core.service.ChunkingService;
import com.ragvault.core.service.SchemaInspectorService;
import com.ragvault.core.service.DataSourceConfigService;
import com.ragvault.core.service.RagTableConfigService;
import com.ragvault.core.service.SensitivityAnalysisService;
import com.ragvault.core.service.RagColumnSuggestionService;
import com.ragvault.core.service.SchemaDescriptionService;
import com.ragvault.core.service.SqlGeneratorService;
import com.ragvault.core.service.DataSourceRouterService;
import com.ragvault.core.service.RoutingEmbeddingService;
import com.ragvault.core.service.QueryIntent;



import com.ragvault.core.domain.DataSourceConfig;
import com.ragvault.widget.domain.DsSyncJob;
import com.ragvault.core.domain.RagTableConfig;
import com.ragvault.widget.repository.DsSyncJobRepository;
import com.ragvault.core.repository.RagTableConfigRepository;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 초기 전체 동기화 서비스 (rag_table_config 경로).
 *
 * 처리 흐름:
 * 1. 대상 테이블 SELECT * (streaming)
 * 2. 병렬 처리 → ChunkingService.processRow
 *
 * 외부 DB 연결은 DataSourceConfigService.openConnection 재사용.
 * binlog/GTID·Discord 알림은 ragvault 범위 밖이라 제거(축소 이식).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InitialSyncService {

    /** 테이블명 allowlist 패턴 — SQL 인젝션 방어. */
    private static final Pattern SAFE_TABLE_NAME =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");

    private final DataSourceConfigService dataSourceConfigService;
    private final RagTableConfigService ragTableConfigService;
    private final RagTableConfigRepository ragTableConfigRepository;
    private final RagColumnSuggestionService ragColumnSuggestionService;
    private final SchemaInspectorService schemaInspector;
    private final ChunkingService chunkingService;
    private final DsSyncJobRepository syncJobRepository;

    private static final int THREAD_COUNT = 8;
    private static final int BATCH_LIMIT = 100;

    /** 동시 재임베딩 작업 수 제한 — LLM·JDBC 리소스 보호 */
    private static final java.util.concurrent.Semaphore RESYNC_SEMAPHORE =
            new java.util.concurrent.Semaphore(3);

    /**
     * LLM 컬럼 재분석 → 설정 업데이트 → 전체 재임베딩을 순차 실행 (단일 @Async 스레드).
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

                // self-invocation: 현재 스레드에서 동기 실행 (LLM 완료 후 임베딩 보장)
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
     * 초기 동기화.
     *
     * @param datasourceId 동기화할 datasource ID (필수)
     * @param tables       동기화할 테이블 목록 (null이면 해당 datasource의 전체 활성 테이블)
     * @param triggeredBy  트리거한 사용자 이메일
     */
    public void runInitialSync(Integer datasourceId, List<String> tables, String triggeredBy) {
        DataSourceConfig ds = dataSourceConfigService.findById(datasourceId);

        for (String tableName : tables == null ? List.<String>of() : tables) {
            RagTableConfig config = ragTableConfigService.findByTable(datasourceId, tableName).orElse(null);
            if (config == null || !java.util.Objects.equals(config.getDatasourceId(), datasourceId)) {
                log.warn("Skip sync — no active rag_table_config: dsId={}, table={}", datasourceId, tableName);
                continue;
            }

            DsSyncJob job = syncJobRepository.save(DsSyncJob.builder()
                    .datasourceId(datasourceId)
                    .tableName(tableName)
                    .status("running")
                    .startedAt(Instant.now())
                    .build());
            try (Connection conn = dataSourceConfigService.openConnection(ds)) {
                int[] counts = processTable(conn, config);
                job.setStatus(counts[1] > 0 ? "partial" : "done");
                job.setRowCount(counts[0]);
                job.setFinishedAt(Instant.now());
            } catch (Exception e) {
                log.error("Initial sync error: dsId={}, table={}, error={}", datasourceId, tableName, e.getMessage(), e);
                job.setStatus("failed");
                job.setErrorMsg(e.getMessage());
                job.setFinishedAt(Instant.now());
            }
            syncJobRepository.save(job);
        }
    }

    /**
     * 테이블 전체 행 처리 (streaming + 병렬).
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
}
