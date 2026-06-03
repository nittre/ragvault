package com.ragservice.rag.controller;

import com.ragservice.rag.domain.SyncJob;
import com.ragservice.rag.repository.BinlogEventRepository;
import com.ragservice.rag.repository.SyncJobRepository;
import com.ragservice.rag.service.BinlogSyncService;
import com.ragservice.rag.service.InitialSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 동기화 관리 Admin API.
 *
 * 접근 권한: api:admin scope (SecurityConfig 설정).
 */
@RestController
@RequestMapping("/api/v1/admin/sync")
@RequiredArgsConstructor
public class AdminSyncController {

    private final BinlogSyncService binlogSyncService;
    private final InitialSyncService initialSyncService;
    private final SyncJobRepository syncJobRepository;
    private final BinlogEventRepository binlogEventRepository;

    /**
     * 수동 즉시 동기화 트리거.
     */
    @PostMapping("/trigger")
    public ResponseEntity<SyncJob> trigger(
            @RequestHeader(value = "X-User-Email", required = false) String email) {
        SyncJob job = binlogSyncService.triggerManualSync(email);
        return ResponseEntity.ok(job);
    }

    /**
     * 최근 동기화 상태 조회.
     */
    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(syncJobRepository.findTopByOrderByStartedAtDesc());
    }

    /**
     * 초기 전체 동기화 트리거 (비동기).
     * 즉시 202 Accepted 반환, 백그라운드에서 처리.
     */
    @PostMapping("/initial")
    public ResponseEntity<Void> initialSync(
            @RequestBody(required = false) Map<String, List<String>> body,
            @RequestHeader(value = "X-User-Email", required = false) String email) {
        List<String> tables = body != null ? body.getOrDefault("tables", null) : null;
        initialSyncService.runInitialSync(tables, email);
        return ResponseEntity.accepted().build();
    }

    /**
     * 실패한 binlog 이벤트 목록 조회.
     */
    @GetMapping("/failed-events")
    public ResponseEntity<?> failedEvents() {
        return ResponseEntity.ok(binlogEventRepository.findByProcessedFalseOrderByCreatedAtAsc());
    }
}
