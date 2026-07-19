package com.ragservice.rag.controller;

import com.ragvault.core.service.BinlogSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RAG 자동 동기화 진행 상태 폴링 API.
 * OFF→ON 전환 후 프론트엔드가 완료 여부를 확인하는 데 사용.
 */
@RestController
@RequestMapping("/api/v1/admin/datasources/{dsId}")
@RequiredArgsConstructor
public class AdminRagSyncStatusController {

    private final BinlogSyncService binlogSyncService;

    @GetMapping("/rag-sync-status")
    public Map<String, Object> ragSyncStatus(@PathVariable Integer dsId) {
        return Map.of("syncing", binlogSyncService.isSyncInProgress(dsId));
    }
}
