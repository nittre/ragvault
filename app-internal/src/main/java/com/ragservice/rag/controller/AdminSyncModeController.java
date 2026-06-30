package com.ragservice.rag.controller;

import com.ragvault.core.domain.DataSourceConfig;
import com.ragservice.rag.service.BinlogSyncService;
import com.ragvault.core.service.DataSourceConfigService;
import com.ragservice.rag.service.WhitelistSyncService;
import com.ragservice.rag.domain.SyncModeConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * 데이터소스별 자동 동기화 모드 관리 API.
 * /api/v1/admin/datasources/{dsId}/sync-mode
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/datasources/{dsId}/sync-mode")
@RequiredArgsConstructor
public class AdminSyncModeController {

    private final WhitelistSyncService whitelistSyncService;
    private final BinlogSyncService binlogSyncService;
    private final DataSourceConfigService dataSourceConfigService;

    @GetMapping
    public SyncModeResponse get(@PathVariable Integer dsId) {
        return buildResponse(dsId);
    }

    @PutMapping
    public SyncModeResponse update(@PathVariable Integer dsId, @RequestBody UpdateRequest req) {
        if (req.autoSyncEnabled()) {
            // disabledAt을 setAutoSync 이전에 캡처 — async 스레드에서 replay 범위 결정에 사용
            java.time.Instant since = whitelistSyncService.getOrDefault(dsId, req.tableType()).getDisabledAt();
            DataSourceConfig ds = dataSourceConfigService.findById(dsId);
            binlogSyncService.triggerSyncAndReplayAsync("sync-mode-toggle", ds, dsId, req.tableType(), since);
        }
        whitelistSyncService.setAutoSync(dsId, req.tableType(), req.autoSyncEnabled());
        return buildResponse(dsId);
    }

    @PostMapping("/replay")
    public WhitelistSyncService.ReplayResult replay(@PathVariable Integer dsId, @RequestBody ReplayRequest req) {
        return whitelistSyncService.replay(dsId, req.tableType());
    }

    @GetMapping("/rag-sync-status")
    public Map<String, Object> ragSyncStatus(@PathVariable Integer dsId) {
        return Map.of("syncing", binlogSyncService.isSyncInProgress(dsId));
    }


    private SyncModeResponse buildResponse(Integer dsId) {
        SyncModeConfig sql = whitelistSyncService.getOrDefault(dsId, "sql");
        SyncModeConfig rag = whitelistSyncService.getOrDefault(dsId, "rag");
        return new SyncModeResponse(
            new ModeEntry(sql.isAutoSyncEnabled(), sql.getDisabledAt()),
            new ModeEntry(rag.isAutoSyncEnabled(), rag.getDisabledAt())
        );
    }

    record UpdateRequest(String tableType, boolean autoSyncEnabled) {}
    record ReplayRequest(String tableType) {}
    record ModeEntry(boolean autoSyncEnabled, Instant disabledAt) {}
    record SyncModeResponse(ModeEntry sql, ModeEntry rag) {}
}
