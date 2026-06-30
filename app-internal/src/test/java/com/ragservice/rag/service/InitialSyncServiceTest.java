package com.ragservice.rag.service;
import com.ragvault.core.service.*;

import com.ragservice.rag.repository.*;
import com.ragvault.core.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * InitialSyncService 단위 테스트.
 *
 * resyncWithColumnUpdateAsync 동시 실행 세마포어(최대 3) 검증.
 */
@ExtendWith(MockitoExtension.class)
class InitialSyncServiceTest {

    @Mock DataSourceConfigService dataSourceConfigService;
    @Mock RagTableConfigService ragTableConfigService;
    @Mock RagTableConfigRepository ragTableConfigRepository;
    @Mock RagColumnSuggestionService ragColumnSuggestionService;
    @Mock SchemaInspectorService schemaInspector;
    @Mock ChunkingService chunkingService;
    @Mock BinlogPositionRepository positionRepository;
    @Mock SyncJobRepository syncJobRepository;
    @Mock DiscordNotifier discordNotifier;

    @InjectMocks InitialSyncService svc;

    /**
     * 6개 스레드가 동시에 resyncWithColumnUpdateAsync를 호출할 때
     * 세마포어(3)로 인해 동시 실행이 최대 3개로 제한되는지 검증.
     *
     * schemaInspector.getAllTablesWithSchema 호출 시점에서 동시 실행 수를 측정하고,
     * 이후 RuntimeException을 던져 runInitialSync 호출을 우회한다.
     */
    @Test
    void resyncSemaphore_limitsTo3Concurrent() throws InterruptedException {
        AtomicInteger peakConcurrent = new AtomicInteger(0);
        AtomicInteger current = new AtomicInteger(0);
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch atBlocker = new CountDownLatch(3); // 세마포어 3개 모두 점유 신호
        int taskCount = 6;
        CountDownLatch done = new CountDownLatch(taskCount);

        // findBySourceTableAndDatasourceId → empty (llmStatus 업데이트 없음)
        lenient().when(ragTableConfigRepository.findBySourceTableAndDatasourceId(any(), any()))
                .thenReturn(Optional.empty());

        // getAllTablesWithSchema: 동시 진입 수를 측정하고, blocker 해제 전까지 블로킹
        // RuntimeException으로 runInitialSync 호출을 우회한다
        lenient().when(schemaInspector.getAllTablesWithSchema(any())).thenAnswer(inv -> {
            int c = current.incrementAndGet();
            peakConcurrent.accumulateAndGet(c, Math::max);
            atBlocker.countDown();
            blocker.await(3, TimeUnit.SECONDS);
            current.decrementAndGet();
            throw new RuntimeException("test-abort");
        });

        ExecutorService pool = Executors.newFixedThreadPool(taskCount);
        for (int i = 0; i < taskCount; i++) {
            final String table = "t" + i;
            pool.submit(() -> {
                try {
                    svc.resyncWithColumnUpdateAsync(1, table, "test");
                } finally {
                    done.countDown();
                }
            });
        }

        // 3개 스레드가 세마포어를 점유하고 blocker에 도달할 때까지 대기
        boolean reachedBlocker = atBlocker.await(5, TimeUnit.SECONDS);
        assertThat(reachedBlocker).as("3개 스레드가 세마포어를 점유해야 합니다").isTrue();

        blocker.countDown(); // 블로킹 해제 → 모든 작업 완료
        boolean allFinished = done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(allFinished).as("6개 작업이 모두 완료되어야 합니다").isTrue();
        assertThat(peakConcurrent.get())
                .as("최대 동시 실행은 세마포어 한도(3)를 초과할 수 없습니다")
                .isLessThanOrEqualTo(3);
    }
}
