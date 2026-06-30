package com.ragservice.rag.controller;

import com.ragservice.rag.repository.DdlEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * DDL 이벤트 조회 Admin API.
 *
 * 접근 권한: api:admin scope (SecurityConfig 설정).
 */
@RestController
@RequestMapping("/api/v1/admin/datasources/{dsId}/ddl-events")
@RequiredArgsConstructor
public class AdminDdlController {

    private final DdlEventRepository ddlEventRepository;

    /**
     * DDL 이벤트 목록 조회.
     *
     * @param all true면 전체 이력, false(기본)면 미처리만
     */
    @GetMapping
    public ResponseEntity<?> list(
            @PathVariable Integer dsId,
            @RequestParam(defaultValue = "false") boolean all) {
        return all
                ? ResponseEntity.ok(ddlEventRepository.findByDatasourceIdOrderByCreatedAtDesc(dsId))
                : ResponseEntity.ok(ddlEventRepository.findByDatasourceIdAndProcessedAtIsNullOrderByCreatedAtDesc(dsId));
    }
}
