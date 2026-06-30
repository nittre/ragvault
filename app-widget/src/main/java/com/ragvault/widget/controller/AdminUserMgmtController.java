package com.ragvault.widget.controller;

import com.ragvault.core.domain.RagRole;
import com.ragvault.core.domain.RagUser;
import com.ragvault.widget.service.AuditLogService;
import com.ragvault.core.service.RagUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * rag_users 기반 사용자 관리 Admin API.
 *
 * GET    /admin/users         → api:admin   (목록 조회)
 * POST   /admin/users         → api:super-admin (생성)
 * PUT    /admin/users/{email} → api:super-admin (수정)
 * DELETE /admin/users/{email} → api:super-admin (삭제)
 *
 * 권한은 SecurityConfig 의 authorizeHttpRequests 에서 강제.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserMgmtController {

    private final RagUserService ragUserService;
    private final AuditLogService auditLogService;

    record UserResponse(
            String email,
            String name,
            String role,
            boolean active,
            String createdBy,
            String createdAt
    ) {}

    record CreateUserRequest(String email, String name, RagRole role) {}

    record UpdateUserRequest(String name, RagRole role, boolean active) {}

    @GetMapping
    public ResponseEntity<List<UserResponse>> list() {
        List<UserResponse> result = ragUserService.listUsers().stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(
            @RequestBody CreateUserRequest req,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        String createdBy = authentication != null ? authentication.getName() : "unknown";
        RagUser user = ragUserService.createUser(req.email(), req.name(), req.role(), createdBy);
        auditLogService.log(createdBy, "USER_CREATE", "rag_user", req.email(), null,
                httpRequest.getRemoteAddr());
        return ResponseEntity.status(201).body(toResponse(user));
    }

    @PutMapping("/{email}")
    public ResponseEntity<UserResponse> update(
            @PathVariable String email,
            @RequestBody UpdateUserRequest req,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        String updatedBy = authentication != null ? authentication.getName() : "unknown";
        RagUser user = ragUserService.updateUser(email, req.name(), req.role(), req.active(), updatedBy);
        auditLogService.log(updatedBy, "USER_UPDATE", "rag_user", email, null,
                httpRequest.getRemoteAddr());
        return ResponseEntity.ok(toResponse(user));
    }

    @DeleteMapping("/{email}")
    public ResponseEntity<Void> delete(@PathVariable String email,
                                        Authentication authentication,
                                        HttpServletRequest httpRequest) {
        String actor = authentication != null ? authentication.getName() : "unknown";
        ragUserService.deleteUser(email);
        auditLogService.log(actor, "USER_DELETE", "rag_user", email, null,
                httpRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

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
