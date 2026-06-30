package com.ragservice.rag.controller;

import com.ragvault.core.domain.SqlTableConfig;
import com.ragvault.core.repository.SqlTableConfigRepository;
import com.ragvault.core.service.SchemaInspectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SQL 대상 테이블 화이트리스트 관리 Admin API.
 *
 * api:admin scope 필요 (SecurityConfig 에서 강제).
 * restricted 민감도 테이블은 등록 거부.
 *
 * ADR-0007: sql_table_config 기반 컬럼 접근 제어
 * requirements/08-text-to-sql.md
 */
@RestController
@RequestMapping("/api/v1/admin/sql-tables")
@RequiredArgsConstructor
public class AdminSqlTableController {

    private final SqlTableConfigRepository repository;
    private final SchemaInspectorService schemaInspector;

    @GetMapping
    public List<SqlTableConfig> list() {
        // 관리 화면에서는 비활성 테이블도 보여야 재활성화가 가능하므로 전체 반환.
        // (SqlValidator 화이트리스트는 별도로 findByIsActiveTrue() 만 사용한다)
        return repository.findAllByOrderByIdAsc();
    }

    @PostMapping
    public SqlTableConfig create(@RequestBody SqlTableConfig config) {
        if ("restricted".equals(config.getDataSensitivity())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "restricted 테이블은 SQL 경로에 등록할 수 없습니다");
        }
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        SqlTableConfig saved = repository.save(config);
        // 신규 등록 테이블의 MySQL 스키마가 즉시 반영되도록 캐시 무효화
        if (saved.getDatasourceId() != null) {
            schemaInspector.evictSchemaCache(saved.getDatasourceId());
        }
        return saved;
    }

    @PatchMapping("/{id}")
    public SqlTableConfig update(@PathVariable Integer id, @RequestBody SqlTableConfig update) {
        SqlTableConfig existing = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "테이블 설정을 찾을 수 없습니다"));
        existing.setDisplayName(update.getDisplayName());
        existing.setDescription(update.getDescription());
        existing.setAllowedColumns(update.getAllowedColumns());
        existing.setExcludedColumns(update.getExcludedColumns());
        existing.setSampleQueries(update.getSampleQueries());
        existing.setActive(update.isActive());
        existing.setUpdatedAt(LocalDateTime.now());
        // 스키마 캐시 무효화
        if (existing.getDatasourceId() != null) {
            schemaInspector.evictSchemaCache(existing.getDatasourceId());
        }
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        repository.findById(id).ifPresent(config -> {
            if (config.getDatasourceId() != null) {
                schemaInspector.evictSchemaCache(config.getDatasourceId());
            }
        });
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 스키마 캐시 수동 갱신 (Redis 1h 캐시 즉시 무효화).
     * 쿼리 파라미터: datasourceId (필수)
     */
    @PostMapping("/refresh-schema-cache")
    public ResponseEntity<Void> refreshSchemaCache(
            @RequestParam Integer datasourceId) {
        schemaInspector.evictSchemaCache(datasourceId);
        return ResponseEntity.ok().build();
    }
}
