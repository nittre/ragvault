package com.ragvault.widget.controller;

import com.ragvault.widget.domain.AuditLog;
import com.ragvault.widget.dto.AuditLogDto;
import com.ragvault.widget.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 감사 로그 조회 Admin API.
 *
 * GET /admin/audit?page=0&size=30 → Page<AuditLogDto>
 *
 * /admin/** 는 SecurityConfig 에서 JWT 인증 필수로 보호됨.
 */
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    public ResponseEntity<Page<AuditLogDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLog> logs = auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        return ResponseEntity.ok(logs.map(AuditLogDto::from));
    }
}
