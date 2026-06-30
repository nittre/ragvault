package com.ragvault.widget.controller;

import com.ragvault.core.domain.DataSourceConfig;
import com.ragvault.widget.domain.DsSyncJob;
import com.ragvault.widget.repository.DsSyncJobRepository;
import com.ragvault.core.service.DataSourceConfigService;
import com.ragvault.core.service.DataSourceConfigService.ConnectionTestResult;
import com.ragvault.core.dto.DataSourceRequest;
import com.ragvault.widget.service.DsSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 외부 MySQL/MariaDB 데이터소스 관리 + 동기화 Admin API.
 *
 * RAG 테이블 관리는 AdminDsRagTableController(/api/admin/datasources/{dsId}/rag-tables)에서 담당.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/datasources")
@RequiredArgsConstructor
public class AdminDataSourceController {

    private final DataSourceConfigService dsConfigService;
    private final DsSyncService dsSyncService;
    private final DsSyncJobRepository syncJobRepository;

    // -----------------------------------------------------------------------
    // 응답/요청 레코드
    // -----------------------------------------------------------------------

    record DataSourceResponse(Integer id, String name, String description, String dbType,
                              String host, int port, String dbName, String username,
                              boolean isActive, Instant createdAt, Instant updatedAt) {}
    record SyncJobResponse(Integer id, Integer datasourceId, String tableName, String status,
                           Integer rowCount, String errorMsg, Instant startedAt, Instant finishedAt) {}

    private DataSourceResponse toResponse(DataSourceConfig ds) {
        return new DataSourceResponse(ds.getId(), ds.getName(), ds.getDescription(), ds.getDbType(),
                ds.getHost(), ds.getPort(), ds.getDbName(), ds.getUsername(),
                ds.isActive(), ds.getCreatedAt(), ds.getUpdatedAt());
    }

    private SyncJobResponse toResponse(DsSyncJob j) {
        return new SyncJobResponse(j.getId(), j.getDatasourceId(), j.getTableName(), j.getStatus(),
                j.getRowCount(), j.getErrorMsg(), j.getStartedAt(), j.getFinishedAt());
    }

    // -----------------------------------------------------------------------
    // 데이터소스 CRUD
    // -----------------------------------------------------------------------

    @GetMapping
    public ResponseEntity<List<DataSourceResponse>> list() {
        return ResponseEntity.ok(dsConfigService.findAll().stream().map(this::toResponse).toList());
    }

    @PostMapping
    public ResponseEntity<DataSourceResponse> create(@RequestBody DataSourceRequest req) {
        DataSourceConfig saved = dsConfigService.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<DataSourceResponse> update(@PathVariable Integer id,
                                                     @RequestBody DataSourceRequest req) {
        return ResponseEntity.ok(toResponse(dsConfigService.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        dsConfigService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<ConnectionTestResult> test(@PathVariable Integer id) {
        return ResponseEntity.ok(dsConfigService.testConnection(id));
    }

    // -----------------------------------------------------------------------
    // 외부 DB 테이블 목록 조회 (INFORMATION_SCHEMA)
    // -----------------------------------------------------------------------

    @GetMapping("/{id}/tables")
    public ResponseEntity<List<String>> tables(@PathVariable Integer id) {
        DataSourceConfig ds = dsConfigService.findById(id);
        List<String> tableNames = new ArrayList<>();
        try (Connection conn = dsConfigService.openConnection(ds)) {
            String sql = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ds.getDbName());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) tableNames.add(rs.getString(1));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list tables for datasource id={}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        return ResponseEntity.ok(tableNames);
    }

    // -----------------------------------------------------------------------
    // 동기화 트리거 + 이력
    // -----------------------------------------------------------------------

    @PostMapping("/{id}/sync/{tableName}")
    public ResponseEntity<Void> sync(@PathVariable Integer id, @PathVariable String tableName) {
        dsConfigService.findById(id); // 존재 검증
        dsSyncService.syncTableAsync(id, tableName);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}/sync-jobs")
    public ResponseEntity<List<SyncJobResponse>> syncJobs(@PathVariable Integer id) {
        return ResponseEntity.ok(
                syncJobRepository.findByDatasourceIdOrderByStartedAtDesc(id).stream()
                        .map(this::toResponse).toList());
    }
}
