package com.ragvault.widget.controller;

import com.ragvault.core.domain.RagTableConfig;
import com.ragvault.core.repository.RagTableConfigRepository;
import com.ragvault.widget.service.InitialSyncService;
import com.ragvault.core.service.RagColumnSuggestionService;
import com.ragvault.core.service.RagTableConfigService;
import com.ragvault.core.service.SchemaInspectorService;
import com.ragvault.widget.service.WhitelistSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 데이터소스별 RAG 테이블 관리 API.
 * /admin/datasources/{dsId}/rag-tables
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/datasources/{dsId}/rag-tables")
@RequiredArgsConstructor
public class AdminDsRagTableController {

    private final RagTableConfigService ragTableConfigService;
    private final RagTableConfigRepository repository;
    private final InitialSyncService initialSyncService;
    private final SchemaInspectorService schemaInspector;
    private final RagColumnSuggestionService ragColumnSuggestionService;
    private final WhitelistSyncService whitelistSyncService;

    private static final Set<String> TITLE_KEYWORDS =
            Set.of("title", "name", "subject", "label", "heading", "caption", "summary");
    private static final Set<String> CONTENT_TYPES =
            Set.of("varchar", "text", "mediumtext", "longtext", "char", "tinytext");

    @GetMapping
    public List<RagTableConfig> list(@PathVariable Integer dsId) {
        return repository.findByDatasourceIdOrderByIdAsc(dsId);
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable Integer dsId, @RequestBody RagTableConfig config) {
        config.setDatasourceId(dsId);
        if (config.getContentColumnsJson() == null) config.setContentColumnsJson("");
        if (config.getMetadataColumnsJson() == null) config.setMetadataColumnsJson("");
        try {
            return ResponseEntity.ok(ragTableConfigService.register(config));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{sourceTable}")
    public ResponseEntity<Void> delete(@PathVariable Integer dsId, @PathVariable String sourceTable) {
        ragTableConfigService.deactivate(sourceTable, dsId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{sourceTable}/resync")
    public ResponseEntity<Void> resync(
            @PathVariable Integer dsId,
            @PathVariable String sourceTable,
            @RequestHeader(value = "X-User-Email", required = false) String email) {
        repository.findBySourceTableAndDatasourceId(sourceTable, dsId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "테이블 설정을 찾을 수 없습니다: " + sourceTable));
        initialSyncService.resyncWithColumnUpdateAsync(dsId, sourceTable, email);
        return ResponseEntity.accepted().build();
    }

    @PatchMapping("/{sourceTable}/status")
    public ResponseEntity<RagTableConfig> updateStatus(
            @PathVariable Integer dsId,
            @PathVariable String sourceTable,
            @RequestBody StatusRequest req) {
        RagTableConfig config = repository.findBySourceTableAndDatasourceId(sourceTable, dsId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "테이블 설정을 찾을 수 없습니다: " + sourceTable));
        config.setActive(req.isActive());
        return ResponseEntity.ok(repository.save(config));
    }

    /**
     * 컬럼 설정 수정 (title / content / metadata / pk / 청킹 파라미터).
     */
    @PatchMapping("/{sourceTable}/columns")
    public ResponseEntity<RagTableConfig> updateColumns(
            @PathVariable Integer dsId,
            @PathVariable String sourceTable,
            @RequestBody ColumnConfigRequest req) {
        RagTableConfig config = repository.findBySourceTableAndDatasourceId(sourceTable, dsId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "테이블 설정을 찾을 수 없습니다: " + sourceTable));

        config.setTitleColumn(req.titleColumn());
        config.setContentColumnsJson(
                req.contentColumns() == null ? "" : String.join(",", req.contentColumns()));
        config.setMetadataColumnsJson(
                req.metadataColumns() == null ? "" : String.join(",", req.metadataColumns()));
        if (req.pkColumn() != null && !req.pkColumn().isBlank())
            config.setPkColumn(req.pkColumn());
        if (req.chunkingStrategy() != null && !req.chunkingStrategy().isBlank())
            config.setChunkingStrategy(req.chunkingStrategy());
        if (req.chunkSize() > 0) config.setChunkSize(req.chunkSize());
        if (req.chunkOverlap() >= 0) config.setChunkOverlap(req.chunkOverlap());

        RagTableConfig saved = repository.save(config);
        ragTableConfigService.refreshCache();
        return ResponseEntity.ok(saved);
    }

    /**
     * 스키마 탐색 결과에서 선택한 테이블을 일괄 등록.
     */
    @PostMapping("/bulk-import")
    public ResponseEntity<BulkImportResult> bulkImport(
            @PathVariable Integer dsId,
            @RequestBody BulkImportRequest req) {

        Set<String> requested = Set.copyOf(req.tableNames());
        List<SchemaInspectorService.TableInfo> allSchemas = schemaInspector.getAllTablesWithSchema(dsId);
        Map<String, SchemaInspectorService.TableInfo> schemaMap = allSchemas.stream()
                .collect(Collectors.toMap(SchemaInspectorService.TableInfo::tableName, t -> t));

        List<SchemaInspectorService.TableInfo> selectedSchemas = allSchemas.stream()
                .filter(t -> requested.contains(t.tableName()))
                .collect(Collectors.toList());

        List<String> imported = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (String tableName : req.tableNames()) {
            try {
                RagTableConfig config = new RagTableConfig();
                config.setSourceTable(tableName);
                config.setSourceType("mysql");
                config.setDatasourceId(dsId);
                config.setDataSensitivity("internal");
                config.setChunkingStrategy("recursive");
                config.setChunkSize(500);
                config.setChunkOverlap(50);
                config.setLlmStatus("pending");
                autoDetectColumns(config, schemaMap.get(tableName));

                ragTableConfigService.register(config);
                imported.add(tableName);
            } catch (Exception e) {
                log.warn("RAG bulk import skipped: table={}, dsId={}, reason={}", tableName, dsId, e.getMessage());
                skipped.add(tableName);
            }
        }

        if (!imported.isEmpty()) {
            ragColumnSuggestionService.suggestAndUpdateAsync(dsId, imported, selectedSchemas);
        }

        return ResponseEntity.ok(new BulkImportResult(imported, skipped));
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private void autoDetectColumns(RagTableConfig config, SchemaInspectorService.TableInfo tableInfo) {
        if (tableInfo == null) {
            config.setPkColumn("id");
            config.setContentColumnsJson("");
            config.setMetadataColumnsJson("");
            return;
        }

        List<SchemaInspectorService.ColumnDetail> cols = tableInfo.columns();

        String pk = cols.stream()
                .filter(SchemaInspectorService.ColumnDetail::primaryKey)
                .map(SchemaInspectorService.ColumnDetail::name)
                .findFirst()
                .orElse("id");
        config.setPkColumn(pk);

        String finalPk = pk;
        String title = cols.stream()
                .filter(c -> !c.name().equals(finalPk))
                .filter(c -> TITLE_KEYWORDS.stream().anyMatch(kw -> c.name().toLowerCase().contains(kw)))
                .map(SchemaInspectorService.ColumnDetail::name)
                .findFirst()
                .orElse(null);
        config.setTitleColumn(title);

        List<String> contentCols = cols.stream()
                .filter(c -> !c.name().equals(finalPk))
                .filter(c -> CONTENT_TYPES.contains(c.dataType().toLowerCase()))
                .map(SchemaInspectorService.ColumnDetail::name)
                .collect(Collectors.toList());
        config.setContentColumnsJson(String.join(",", contentCols));

        Set<String> contentSet = new HashSet<>(contentCols);
        List<String> metaCols = cols.stream()
                .filter(c -> !c.name().equals(finalPk) && !contentSet.contains(c.name()))
                .map(SchemaInspectorService.ColumnDetail::name)
                .collect(Collectors.toList());
        config.setMetadataColumnsJson(String.join(",", metaCols));
    }

    /** 선택한 테이블 일괄 삭제. */
    @DeleteMapping("/bulk")
    public ResponseEntity<BulkActionResult> bulkDelete(
            @PathVariable Integer dsId,
            @RequestBody BulkTableNamesRequest req) {
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String tableName : req.tableNames()) {
            try {
                ragTableConfigService.deactivate(tableName, dsId);
                deleted.add(tableName);
            } catch (Exception e) {
                log.warn("RAG bulk delete failed: table={}, dsId={}, reason={}", tableName, dsId, e.getMessage());
                failed.add(tableName);
            }
        }
        return ResponseEntity.ok(new BulkActionResult(deleted, failed));
    }

    /** 선택한 테이블 일괄 재동기화 (비동기). */
    @PostMapping("/bulk-resync")
    public ResponseEntity<BulkActionResult> bulkResync(
            @PathVariable Integer dsId,
            @RequestBody BulkTableNamesRequest req,
            @RequestHeader(value = "X-User-Email", required = false) String email) {
        List<String> started = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String tableName : req.tableNames()) {
            try {
                repository.findBySourceTableAndDatasourceId(tableName, dsId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, tableName));
                initialSyncService.resyncWithColumnUpdateAsync(dsId, tableName, email);
                started.add(tableName);
            } catch (Exception e) {
                log.warn("RAG bulk resync failed: table={}, dsId={}, reason={}", tableName, dsId, e.getMessage());
                failed.add(tableName);
            }
        }
        return ResponseEntity.ok(new BulkActionResult(started, failed));
    }

    @GetMapping("/drift-status")
    public List<WhitelistSyncService.DriftEntry> driftStatus(@PathVariable Integer dsId) {
        return whitelistSyncService.getDriftStatus(dsId, "rag");
    }

    record StatusRequest(boolean isActive) {}

    record BulkImportRequest(List<String> tableNames) {}

    record BulkImportResult(List<String> imported, List<String> skipped) {}

    record BulkTableNamesRequest(List<String> tableNames) {}

    record BulkActionResult(List<String> succeeded, List<String> failed) {}

    record ColumnConfigRequest(
            String titleColumn,
            List<String> contentColumns,
            List<String> metadataColumns,
            String pkColumn,
            String chunkingStrategy,
            int chunkSize,
            int chunkOverlap
    ) {}
}
