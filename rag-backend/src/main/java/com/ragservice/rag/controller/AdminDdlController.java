package com.ragservice.rag.controller;

import com.ragservice.rag.dto.DdlAnalysisResult;
import com.ragservice.rag.repository.DdlEventRepository;
import com.ragservice.rag.service.DdlAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * DDL 이벤트 관리 Admin API.
 *
 * 접근 권한: api:admin scope (SecurityConfig 설정).
 */
@RestController
@RequestMapping("/api/v1/admin/ddl-events")
@RequiredArgsConstructor
@Slf4j
public class AdminDdlController {

    private final DdlEventRepository ddlEventRepository;
    private final DdlAnalysisService ddlAnalysisService;

    /**
     * DDL 이벤트 목록 조회.
     *
     * @param all true면 처리된 것 포함 전체 조회, false(기본)면 미처리만
     */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "false") boolean all) {
        return all
                ? ResponseEntity.ok(ddlEventRepository.findAll())
                : ResponseEntity.ok(ddlEventRepository.findByProcessedAtIsNullOrderByCreatedAtDesc());
    }

    /**
     * DDL 이벤트 영향 분석 결과 조회.
     *
     * GET /api/v1/admin/ddl-events/{id}/analysis
     */
    @GetMapping("/{id}/analysis")
    public ResponseEntity<DdlAnalysisResult> analyze(@PathVariable Long id) {
        return ddlEventRepository.findById(id)
                .map(e -> ResponseEntity.ok(ddlAnalysisService.analyze(e)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 재동기화 트리거.
     *
     * POST /api/v1/admin/ddl-events/{id}/trigger
     *
     * Phase 0: processed_at·processed_by·action_taken='resync_triggered' 마킹 + 로그.
     * 실제 재동기화는 AdminSyncController 를 통해 별도 호출한다.
     */
    @PostMapping("/{id}/trigger")
    public ResponseEntity<Void> triggerResync(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Email", required = false) String email) {
        ddlEventRepository.findById(id).ifPresent(e -> {
            e.setProcessedAt(Instant.now());
            e.setProcessedBy(email);
            e.setActionTaken("resync_triggered");
            ddlEventRepository.save(e);
            log.info("DDL event {} resync triggered by {}", id, email);
        });
        return ResponseEntity.noContent().build();
    }

    /**
     * DDL 이벤트 무시(dismiss) 처리.
     */
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Void> dismiss(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Email", required = false) String email) {
        ddlEventRepository.findById(id).ifPresent(e -> {
            e.setProcessedAt(Instant.now());
            e.setProcessedBy(email);
            e.setActionTaken("ignored");
            ddlEventRepository.save(e);
        });
        return ResponseEntity.noContent().build();
    }
}
