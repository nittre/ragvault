package com.ragservice.rag.controller;

import com.ragvault.core.domain.DataSourceConfig;
import com.ragvault.core.domain.SyncModeConfig;
import com.ragvault.core.service.BinlogSyncService;
import com.ragvault.core.service.DataSourceConfigService;
import com.ragvault.core.service.WhitelistSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AdminSyncModeController 단위 테스트.
 *
 * 검증 포인트:
 * - OFF→ON 전환: triggerSyncAndReplayAsync 호출 + setAutoSync(true) 호출
 * - OFF→ON 전환: triggerManualSync(블로킹) 미호출 — HTTP 스레드 블로킹 방지
 * - ON→OFF 전환: setAutoSync(false)만 호출, binlog sync 미호출
 * - async 메서드에 disabledAt이 정확히 전달되는지 검증
 */
@ExtendWith(MockitoExtension.class)
class AdminSyncModeControllerTest {

    @Mock WhitelistSyncService whitelistSyncService;
    @Mock BinlogSyncService binlogSyncService;
    @Mock DataSourceConfigService dataSourceConfigService;

    @InjectMocks AdminSyncModeController controller;

    private static final int DS_ID = 1;
    private final Instant disabledAt = Instant.now().minusSeconds(1800);

    private DataSourceConfig datasource;

    @BeforeEach
    void setUp() {
        datasource = new DataSourceConfig();
        datasource.setId(DS_ID);

        // getOrDefault 기본 응답: sql 모드 OFF 상태
        SyncModeConfig sqlOff = SyncModeConfig.builder()
                .datasourceId(DS_ID).tableType("sql")
                .autoSyncEnabled(false).disabledAt(disabledAt).build();
        lenient().when(whitelistSyncService.getOrDefault(DS_ID, "sql")).thenReturn(sqlOff);

        // buildResponse를 위한 rag 모드 기본 응답
        SyncModeConfig ragOff = SyncModeConfig.builder()
                .datasourceId(DS_ID).tableType("rag")
                .autoSyncEnabled(false).build();
        lenient().when(whitelistSyncService.getOrDefault(DS_ID, "rag")).thenReturn(ragOff);

        lenient().when(dataSourceConfigService.findById(DS_ID)).thenReturn(datasource);
        lenient().when(whitelistSyncService.setAutoSync(anyInt(), anyString(), anyBoolean()))
                .thenReturn(sqlOff);
    }

    // ── OFF→ON 전환 ────────────────────────────────────────────────────────────

    @Test
    void offToOn_firesAsyncAndSetsAutoSync() {
        controller.update(DS_ID, new AdminSyncModeController.UpdateRequest("sql", true));

        // async 메서드 1회 호출
        verify(binlogSyncService, times(1)).triggerSyncAndReplayAsync(
                eq("sync-mode-toggle"), eq(datasource), eq(DS_ID), eq("sql"), eq(disabledAt));

        // setAutoSync(true) 호출
        verify(whitelistSyncService).setAutoSync(DS_ID, "sql", true);
    }

    @Test
    void offToOn_doesNotCallBlockingTriggerManualSync() {
        controller.update(DS_ID, new AdminSyncModeController.UpdateRequest("sql", true));

        // 블로킹 메서드 미호출 — HTTP 스레드를 30s 점유하는 버그 방지
        verify(binlogSyncService, never()).triggerManualSync(any(), any());
    }

    @Test
    void offToOn_passesDisabledAtToAsync() {
        controller.update(DS_ID, new AdminSyncModeController.UpdateRequest("sql", true));

        ArgumentCaptor<Instant> sinceCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(binlogSyncService).triggerSyncAndReplayAsync(
                any(), any(), anyInt(), anyString(), sinceCaptor.capture());

        // getOrDefault에서 가져온 disabledAt 이 async 메서드로 정확히 전달되어야 함
        assertThat(sinceCaptor.getValue()).isEqualTo(disabledAt);
    }

    @Test
    void offToOn_doesNotCallReplaySeparately() {
        controller.update(DS_ID, new AdminSyncModeController.UpdateRequest("sql", true));

        // replay는 async 메서드 내부에서만 호출. 컨트롤러에서 직접 호출 금지
        verify(whitelistSyncService, never()).replay(anyInt(), anyString());
    }

    // ── ON→OFF 전환 ────────────────────────────────────────────────────────────

    @Test
    void onToOff_onlySetsAutoSync_noBinlogSync() {
        SyncModeConfig sqlOn = SyncModeConfig.builder()
                .datasourceId(DS_ID).tableType("sql")
                .autoSyncEnabled(true).build();
        when(whitelistSyncService.getOrDefault(DS_ID, "sql")).thenReturn(sqlOn);

        controller.update(DS_ID, new AdminSyncModeController.UpdateRequest("sql", false));

        verify(whitelistSyncService).setAutoSync(DS_ID, "sql", false);
        verify(binlogSyncService, never()).triggerSyncAndReplayAsync(any(), any(), any(), any(), any());
        verify(binlogSyncService, never()).triggerManualSync(any(), any());
    }

    // ── GET ────────────────────────────────────────────────────────────────────

    @Test
    void get_returnsSyncModeResponse() {
        var response = controller.get(DS_ID);

        assertThat(response).isNotNull();
        assertThat(response.sql()).isNotNull();
        assertThat(response.rag()).isNotNull();
        verify(whitelistSyncService, times(2)).getOrDefault(eq(DS_ID), anyString());
    }
}
