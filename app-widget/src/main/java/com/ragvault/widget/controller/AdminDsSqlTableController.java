package com.ragvault.widget.controller;

import com.ragvault.core.domain.SqlColumnDescription;
import com.ragvault.core.domain.SqlTableConfig;
import com.ragvault.core.repository.SqlColumnDescriptionRepository;
import com.ragvault.core.repository.SqlTableConfigRepository;
import com.ragvault.core.service.RoutingEmbeddingService;
import com.ragvault.core.service.SchemaInspectorService;
import com.ragvault.core.service.SensitivityAnalysisService;
import com.ragvault.core.service.WhitelistSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 데이터소스별 SQL 테이블 화이트리스트 관리 API.
 * /admin/datasources/{dsId}/sql-tables
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/datasources/{dsId}/sql-tables")
@RequiredArgsConstructor
public class AdminDsSqlTableController {

    private final SqlTableConfigRepository repository;
    private final SqlColumnDescriptionRepository columnDescriptionRepository;
    private final SchemaInspectorService schemaInspector;
    private final SensitivityAnalysisService sensitivityAnalysisService;
    private final WhitelistSyncService whitelistSyncService;
    private final RoutingEmbeddingService routingEmbeddingService;

    @GetMapping
    public List<SqlTableConfig> list(@PathVariable Integer dsId) {
        return repository.findByDatasourceIdOrderByIdAsc(dsId);
    }

    @PostMapping
    public SqlTableConfig create(@PathVariable Integer dsId, @RequestBody SqlTableConfig config) {
        if ("restricted".equals(config.getDataSensitivity())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "restricted 테이블은 SQL 경로에 등록할 수 없습니다");
        }
        config.setDatasourceId(dsId);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        SqlTableConfig saved = repository.save(config);
        schemaInspector.evictSchemaCache(dsId);
        return saved;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer dsId, @PathVariable Integer id) {
        repository.findById(id).ifPresent(config -> {
            if (!dsId.equals(config.getDatasourceId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 데이터소스의 테이블입니다");
            }
            schemaInspector.evictSchemaCache(dsId);
            repository.delete(config);
        });
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}")
    public ResponseEntity<SqlTableConfig> update(
            @PathVariable Integer dsId,
            @PathVariable Integer id,
            @RequestBody UpdateRequest req) {
        SqlTableConfig config = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "테이블을 찾을 수 없습니다"));
        if (!dsId.equals(config.getDatasourceId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 데이터소스의 테이블입니다");
        }
        if (req.dataSensitivity() != null) {
            config.setDataSensitivity(req.dataSensitivity());
        }
        boolean activeChanged = req.isActive() != null;
        if (activeChanged) {
            config.setActive(req.isActive());
        }
        if (req.displayName() != null) {
            config.setDisplayName(req.displayName());
        }
        boolean descriptionChanged = req.description() != null;
        if (descriptionChanged) {
            config.setDescription(req.description().isBlank() ? null : req.description());
        }
        config.setUpdatedAt(LocalDateTime.now());
        SqlTableConfig saved = repository.save(config);

        // isActive는 schemaCache의 필터 기준(findByDatasourceIdAndIsActiveTrue)이므로 무효화 필요.
        if (activeChanged || descriptionChanged) {
            schemaInspector.evictSchemaCache(dsId);
        }
        if (descriptionChanged) {
            routingEmbeddingService.reindexDatasource(dsId);
        }
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/refresh-schema-cache")
    public ResponseEntity<Void> refreshSchemaCache(@PathVariable Integer dsId) {
        schemaInspector.evictSchemaCache(dsId);
        return ResponseEntity.ok().build();
    }

    /**
     * 테이블의 컬럼 목록 + 현재 설명 반환 (어드민 설명 편집용).
     */
    @GetMapping("/{id}/columns")
    public List<ColumnDescriptionResponse> getColumns(@PathVariable Integer dsId, @PathVariable Integer id) {
        SqlTableConfig config = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "테이블을 찾을 수 없습니다"));
        if (!dsId.equals(config.getDatasourceId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 데이터소스의 테이블입니다");
        }
        String table = config.getSourceTable();

        Map<String, SqlColumnDescription> stored = columnDescriptionRepository
                .findByDatasourceIdAndSourceTable(dsId, table).stream()
                .collect(Collectors.toMap(SqlColumnDescription::getColumnName, d -> d, (a, b) -> a));

        return schemaInspector.getAllTablesWithSchema(dsId).stream()
                .filter(t -> t.tableName().equals(table))
                .findFirst()
                .map(t -> t.columns().stream()
                        .map(c -> {
                            SqlColumnDescription d = stored.get(c.name());
                            return new ColumnDescriptionResponse(
                                    c.name(), c.dataType(),
                                    d != null ? d.getDescription() : (c.comment() == null ? "" : c.comment()),
                                    d != null ? d.getSource() : (c.comment() != null && !c.comment().isBlank() ? "comment" : ""));
                        })
                        .collect(Collectors.toList()))
                .orElse(List.of());
    }

    /**
     * 컬럼 설명 일괄 저장 (source='human') + 스키마 캐시 무효화.
     */
    @PutMapping("/{id}/columns")
    public ResponseEntity<Void> updateColumns(@PathVariable Integer dsId, @PathVariable Integer id,
                                              @RequestBody ColumnUpdateRequest req) {
        SqlTableConfig config = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "테이블을 찾을 수 없습니다"));
        if (!dsId.equals(config.getDatasourceId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 데이터소스의 테이블입니다");
        }
        String table = config.getSourceTable();

        for (ColumnDescriptionItem item : req.columns()) {
            if (item.columnName() == null) continue;
            SqlColumnDescription entity = columnDescriptionRepository
                    .findByDatasourceIdAndSourceTableAndColumnName(dsId, table, item.columnName())
                    .orElseGet(SqlColumnDescription::new);
            String desc = item.description();
            if (desc == null || desc.isBlank()) {
                if (entity.getId() != null) columnDescriptionRepository.delete(entity);
                continue;
            }
            entity.setDatasourceId(dsId);
            entity.setSourceTable(table);
            entity.setColumnName(item.columnName());
            entity.setDescription(desc);
            entity.setSource("human");
            entity.setUpdatedAt(java.time.Instant.now());
            columnDescriptionRepository.save(entity);
        }
        schemaInspector.evictSchemaCache(dsId);
        return ResponseEntity.ok().build();
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
        List<SchemaInspectorService.TableInfo> selectedSchemas = allSchemas.stream()
                .filter(t -> requested.contains(t.tableName()))
                .collect(Collectors.toList());

        List<String> imported = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (String tableName : req.tableNames()) {
            try {
                SqlTableConfig config = new SqlTableConfig();
                config.setSourceTable(tableName);
                config.setDisplayName(tableName);
                config.setDatasourceId(dsId);
                config.setDataSensitivity("internal");
                config.setAllowedGroups(new String[]{"all"});
                config.setActive(true);
                config.setLlmStatus("pending");
                config.setCreatedAt(LocalDateTime.now());
                config.setUpdatedAt(LocalDateTime.now());
                repository.save(config);
                imported.add(tableName);
            } catch (Exception e) {
                log.warn("SQL bulk import skipped: table={}, dsId={}, reason={}", tableName, dsId, e.getMessage());
                skipped.add(tableName);
            }
        }

        if (!imported.isEmpty()) {
            schemaInspector.evictSchemaCache(dsId);
            sensitivityAnalysisService.analyzeAndUpdateAsync(dsId, imported, selectedSchemas);
        }

        return ResponseEntity.ok(new BulkImportResult(imported, skipped));
    }

    @DeleteMapping("/bulk")
    public ResponseEntity<BulkActionResult> bulkDelete(
            @PathVariable Integer dsId,
            @RequestBody BulkIdsRequest req) {
        List<Integer> deleted = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        for (Integer id : req.ids()) {
            try {
                SqlTableConfig config = repository.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, String.valueOf(id)));
                if (!dsId.equals(config.getDatasourceId())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 데이터소스의 테이블입니다");
                }
                repository.delete(config);
                deleted.add(id);
            } catch (Exception e) {
                log.warn("SQL bulk delete failed: id={}, dsId={}, reason={}", id, dsId, e.getMessage());
                failed.add(id);
            }
        }
        if (!deleted.isEmpty()) schemaInspector.evictSchemaCache(dsId);
        return ResponseEntity.ok(new BulkActionResult(deleted, failed));
    }

    @GetMapping("/drift-status")
    public List<WhitelistSyncService.DriftEntry> driftStatus(@PathVariable Integer dsId) {
        return whitelistSyncService.getDriftStatus(dsId, "sql");
    }

    record UpdateRequest(String dataSensitivity, Boolean isActive, String displayName, String description) {}

    record ColumnDescriptionResponse(String columnName, String dataType, String description, String source) {}

    record ColumnDescriptionItem(String columnName, String description) {}

    record ColumnUpdateRequest(List<ColumnDescriptionItem> columns) {}

    record BulkIdsRequest(List<Integer> ids) {}

    record BulkActionResult(List<Integer> succeeded, List<Integer> failed) {}

    record BulkImportRequest(List<String> tableNames) {}

    record BulkImportResult(List<String> imported, List<String> skipped) {}
}
