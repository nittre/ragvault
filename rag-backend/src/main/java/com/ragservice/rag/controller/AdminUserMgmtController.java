package com.ragservice.rag.controller;

import com.ragservice.rag.domain.RagRole;
import com.ragservice.rag.domain.RagUser;
import com.ragservice.rag.service.RagUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * rag_users 기반 사용자 관리 Admin API.
 *
 * GET  /api/v1/admin/users        → api:admin (SecurityConfig)
 * POST /api/v1/admin/users        → api:super-admin (SecurityConfig)
 * PUT  /api/v1/admin/users/{email} → api:super-admin (SecurityConfig)
 * DELETE /api/v1/admin/users/{email} → api:super-admin (SecurityConfig)
 *
 * ADR-0002: Phase 0 단순 구조 — rag_users 테이블로 직접 관리.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserMgmtController {

    private final RagUserService ragUserService;

    /** 응답 DTO */
    record UserResponse(
            String email,
            String name,
            String role,
            boolean active,
            String createdBy,
            String createdAt
    ) {}

    /** 전체 사용자 목록 조회 */
    @GetMapping
    public ResponseEntity<List<UserResponse>> list() {
        List<UserResponse> result = ragUserService.listUsers().stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(result);
    }

    /** 사용자 생성 */
    @PostMapping
    public ResponseEntity<UserResponse> create(
            @RequestBody CreateUserRequest req,
            Authentication authentication) {
        String createdBy = authentication != null ? authentication.getName() : "unknown";
        RagUser user = ragUserService.createUser(req.email(), req.name(), req.role(), createdBy);
        return ResponseEntity.status(201).body(toResponse(user));
    }

    /** 사용자 수정 */
    @PutMapping("/{email}")
    public ResponseEntity<UserResponse> update(
            @PathVariable String email,
            @RequestBody UpdateUserRequest req,
            Authentication authentication) {
        String updatedBy = authentication != null ? authentication.getName() : "unknown";
        RagUser user = ragUserService.updateUser(email, req.name(), req.role(), req.active(), updatedBy);
        return ResponseEntity.ok(toResponse(user));
    }

    /** 사용자 삭제 */
    @DeleteMapping("/{email}")
    public ResponseEntity<Void> delete(@PathVariable String email) {
        ragUserService.deleteUser(email);
        return ResponseEntity.noContent().build();
    }

    // ── 요청 DTO ──────────────────────────────────────────────────────────────

    record CreateUserRequest(String email, String name, RagRole role) {}

    record UpdateUserRequest(String name, RagRole role, boolean active) {}

    // ── 내부 변환 ─────────────────────────────────────────────────────────────

    private UserResponse toResponse(RagUser user) {
        return new UserResponse(
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                user.isActive(),
                user.getCreatedBy(),
                user.getCreatedAt() != null ? user.getCreatedAt().toString() : null
        );
    }
}
