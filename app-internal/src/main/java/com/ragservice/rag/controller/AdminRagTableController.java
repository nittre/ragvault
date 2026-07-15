package com.ragservice.rag.controller;

import com.ragvault.core.domain.RagTableConfig;
import com.ragvault.core.repository.RagTableConfigRepository;
import com.ragservice.rag.service.InitialSyncService;
import com.ragvault.core.service.RagTableConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
     * 테이블의 datasourceId를 rag_table_config 에서 자동으로 조회.
     */
    @PostMapping("/{sourceTable}/resync")
    public ResponseEntity<Void> resync(
            @PathVariable String sourceTable,
            @RequestHeader(value = "X-User-Email", required = false) String email) {
        // 레거시 엔드포인트(dsId가 경로에 없음) — 캐시는 dsId로 스코프되므로 여기선
        // DB에서 직접 조회한다. 동일 테이블명이 여러 데이터소스에 있으면 모호할 수 있음.
        RagTableConfig config = ragTableConfigRepository.findBySourceTable(sourceTable)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "테이블 설정을 찾을 수 없습니다: " + sourceTable));
        if (config.getDatasourceId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "테이블에 datasourceId 가 설정되지 않았습니다: " + sourceTable);
        }
        initialSyncService.runInitialSync(config.getDatasourceId(), List.of(sourceTable), email);
        return ResponseEntity.accepted().build();
    }
}
