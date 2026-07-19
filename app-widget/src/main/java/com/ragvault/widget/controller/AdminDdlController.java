package com.ragvault.widget.controller;

import com.ragvault.core.repository.DdlEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * DDL 이벤트 조회 Admin API.
 */
@RestController
@RequestMapping("/api/admin/datasources/{dsId}/ddl-events")
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
