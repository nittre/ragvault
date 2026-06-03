package com.ragservice.rag.controller;

import com.ragservice.rag.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 감사 로그 조회 Admin API.
 * A10 시나리오: 사용자·기간·action 필터 + 페이징
 *
 * 접근 권한: api:admin scope (SecurityConfig 에서 강제)
 *
 * 주의: 원본 응답 조회 (/api/v1/admin/audit-logs/{responseId}/raw) 는
 * AdminRawResponseController 에서 별도 처리 (api:incident-response scope 필요, ADR-0010).
 * 이 컨트롤러는 /api/v1/admin/audit-logs (리스트) 만 담당.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditLogController {

    private final AuditLogRepository repo;

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String userEmail,
            @RequestParam(required = false) String action,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        var result = repo.findFiltered(userEmail, action, from, to, pageable);
        return ResponseEntity.ok(Map.of(
                "data", result.getContent(),
                "total", result.getTotalElements(),
                "page", page,
                "size", size
        ));
    }
}
