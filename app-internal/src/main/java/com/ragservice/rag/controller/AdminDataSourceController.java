package com.ragservice.rag.controller;

import com.ragvault.core.domain.DataSourceConfig;
import com.ragvault.core.dto.DataSourceRequest;
import com.ragservice.rag.dto.DataSourceResponse;
import com.ragvault.core.service.DataSourceAutoSetupService;
import com.ragvault.core.service.DataSourceConfigService;
import com.ragvault.core.service.RoutingEmbeddingService;
import com.ragvault.core.service.SchemaInspectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 멀티 데이터소스 관리 Admin API.
 *
 * 응답은 항상 DataSourceResponse (password_enc 절대 포함 금지).
 *
 * 엔드포인트:
 * GET    /api/v1/admin/datasources          — 전체 목록
 * POST   /api/v1/admin/datasources          — 등록
 * PATCH  /api/v1/admin/datasources/{id}     — 수정
 * DELETE /api/v1/admin/datasources/{id}     — 삭제
 * POST   /api/v1/admin/datasources/{id}/test   — 연결 테스트
 * GET    /api/v1/admin/datasources/{id}/tables — 테이블 목록
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/datasources")
@RequiredArgsConstructor
public class AdminDataSourceController {

    private final DataSourceConfigService dataSourceConfigService;
    private final SchemaInspectorService schemaInspectorService;
    private final DataSourceAutoSetupService dataSourceAutoSetupService;
    private final RoutingEmbeddingService routingEmbeddingService;

    @GetMapping
    public ResponseEntity<List<DataSourceResponse>> findAll() {
        List<DataSourceResponse> list = dataSourceConfigService.findAll().stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(list);
    }

    @PostMapping
    public ResponseEntity<DataSourceResponse> create(@RequestBody DataSourceRequest req) {
        DataSourceConfig created = dataSourceConfigService.create(req);
        log.info("DataSource created: id={}, name={}", created.getId(), created.getName());
        dataSourceAutoSetupService.setupAsync(created.getId(), Boolean.TRUE.equals(req.autoDescribe()));
        return ResponseEntity.ok(toResponse(created));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<DataSourceResponse> update(
            @PathVariable Integer id,
            @RequestBody DataSourceRequest req) {
        DataSourceConfig updated = dataSourceConfigService.update(id, req);
        log.info("DataSource updated: id={}", id);
        // 데이터소스 설명 변경 가능 → 라우팅 임베딩 재색인 (Phase C)
        routingEmbeddingService.reindexDatasource(id);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        dataSourceConfigService.delete(id);
        log.info("DataSource deleted: id={}", id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable Integer id) {
        var result = dataSourceConfigService.testConnection(id);
        return ResponseEntity.ok(Map.of(
                "datasourceId", id,
                "connected", result.connected(),
                "message", result.reason()
        ));
    }

    @GetMapping("/{id}/tables")
    public ResponseEntity<List<SchemaInspectorService.TableInfo>> getTables(@PathVariable Integer id) {
        List<SchemaInspectorService.TableInfo> tables =
                schemaInspectorService.getAllTablesWithSchema(id);
        return ResponseEntity.ok(tables);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * DataSourceConfig → DataSourceResponse 변환.
     * password_enc 는 절대 포함하지 않는다.
     */
    private DataSourceResponse toResponse(DataSourceConfig ds) {
        return new DataSourceResponse(
                ds.getId(),
                ds.getName(),
                ds.getDescription(),
                ds.getDbType(),
                ds.getHost(),
                ds.getPort(),
                ds.getDbName(),
                ds.getUsername(),
                ds.isActive(),
                ds.isSshEnabled(),
                ds.getSshHost(),
                ds.getSshPort(),
                ds.getSshUser(),
                ds.getCreatedAt(),
                ds.getUpdatedAt()
        );
    }
}
