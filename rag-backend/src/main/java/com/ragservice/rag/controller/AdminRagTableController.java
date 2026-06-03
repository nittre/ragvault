package com.ragservice.rag.controller;

import com.ragservice.rag.domain.RagTableConfig;
import com.ragservice.rag.repository.RagTableConfigRepository;
import com.ragservice.rag.service.InitialSyncService;
import com.ragservice.rag.service.RagTableConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * RAG 대상 테이블 관리 Admin API.
 *
 * 접근 권한: api:admin scope (SecurityConfig 설정).
 */
@RestController
@RequestMapping("/api/v1/admin/rag-tables")
@RequiredArgsConstructor
public class AdminRagTableController {

    private final RagTableConfigService ragTableConfigService;
    private final RagTableConfigRepository ragTableConfigRepository;
    private final InitialSyncService initialSyncService;

    /**
     * 새 RAG 대상 테이블 등록.
     * data_sensitivity='restricted' → 400 오류 (ADR-0002, Phase 0 거부).
     */
    @PostMapping
    public ResponseEntity<?> register(@RequestBody RagTableConfig config) {
        try {
            return ResponseEntity.ok(ragTableConfigService.register(config));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * 활성 RAG 대상 테이블 목록 조회.
     */
    @GetMapping
    public ResponseEntity<List<RagTableConfig>> list() {
        return ResponseEntity.ok(ragTableConfigRepository.findAllByIsActiveTrue());
    }

    /**
     * 테이블 비활성화.
     */
    @DeleteMapping("/{sourceTable}")
    public ResponseEntity<Void> deactivate(@PathVariable String sourceTable) {
        ragTableConfigService.deactivate(sourceTable);
        return ResponseEntity.noContent().build();
    }

    /**
     * 특정 테이블 강제 재동기화 (비동기).
     */
    @PostMapping("/{sourceTable}/resync")
    public ResponseEntity<Void> resync(
            @PathVariable String sourceTable,
            @RequestHeader(value = "X-User-Email", required = false) String email) {
        initialSyncService.runInitialSync(List.of(sourceTable), email);
        return ResponseEntity.accepted().build();
    }
}
