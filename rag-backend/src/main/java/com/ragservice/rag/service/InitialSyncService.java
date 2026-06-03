package com.ragservice.rag.service;

import com.ragservice.rag.domain.BinlogPosition;
import com.ragservice.rag.domain.RagTableConfig;
import com.ragservice.rag.domain.SyncJob;
import com.ragservice.rag.repository.BinlogPositionRepository;
import com.ragservice.rag.repository.SyncJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${rag.mysql.host}") private String mysqlHost;
    @Value("${rag.mysql.port:3306}") private int mysqlPort;
    @Value("${rag.mysql.database}") private String mysqlDatabase;
    @Value("${rag.mysql.username}") private String mysqlUsername;
    @Value("${rag.mysql.password}") private String mysqlPassword;

    private final RagTableConfigService ragTableConfigService;
    private final ChunkingService chunkingService;
    private final BinlogPositionRepository positionRepository;
    private final SyncJobRepository syncJobRepository;
    private final DiscordNotifier discordNotifier;

    private static final int THREAD_COUNT = 8;
    private static final int BATCH_LIMIT = 100;

    /**
     * 초기 동기화 (비동기 실행).
     *
     * @param tables      동기화할 테이블 목록 (null이면 전체 활성 테이블)
     * @param triggeredBy 트리거한 사용자 이메일
     */
    @Async
    public CompletableFuture<SyncJob> runInitialSync(List<String> tables, String triggeredBy) {
        SyncJob job = syncJobRepository.save(SyncJob.builder()
                .triggerType("initial")
                .triggeredBy(triggeredBy)
                .status("running")
                .build());

        try (Connection conn = getCustomerConnection()) {
            // 1. 현재 GTID 기록 (스냅샷 직전 — 이후 binlog는 incremental sync가 처리)
            String gtidSet = "";
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT @@global.gtid_executed")) {
                if (rs.next()) gtidSet = rs.getString(1);
            } catch (SQLException e) {
                log.warn("Could not retrieve GTID: {}", e.getMessage());
            }

            // 2. 대상 테이블 결정
            List<RagTableConfig> targets;
            if (tables == null || tables.isEmpty()) {
                targets = ragTableConfigService.findAllActive();
            } else {
                targets = tables.stream()
                        .map(t -> ragTableConfigService.findByTable(t))
                        .filter(java.util.Optional::isPresent)
                        .map(java.util.Optional::get)
                        .toList();
            }

            int totalSuccess = 0, totalFailed = 0;

            // 3. 테이블별 처리
            for (RagTableConfig config : targets) {
                log.info("Initial sync: table={}", config.getSourceTable());
                int[] counts = processTable(conn, config);
                totalSuccess += counts[0];
                totalFailed += counts[1];
                discordNotifier.info("초기 동기화 진행: " + config.getSourceTable() +
                        " 완료 (성공: " + counts[0] + ", 실패: " + counts[1] + ")");
            }

            // 4. GTID 위치 저장
            BinlogPosition pos = positionRepository.findById(1).orElse(new BinlogPosition());
            pos.setId(1);
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
        try (PreparedStatement ps = conn.prepareStatement(sql,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            ps.setFetchSize(Integer.MIN_VALUE);  // streaming result set
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

    private Connection getCustomerConnection() throws SQLException {
        String url = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true",
                mysqlHost, mysqlPort, mysqlDatabase);
        return DriverManager.getConnection(url, mysqlUsername, mysqlPassword);
    }
}
