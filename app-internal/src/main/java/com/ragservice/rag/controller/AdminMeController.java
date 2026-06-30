package com.ragservice.rag.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 현재 로그인 사용자 정보 API.
 *
 * Admin SPA 가 진입 시 역할 확인용으로 사용.
 * GET /api/v1/admin/me → api:admin (SecurityConfig)
 *
 * SecurityContext에서 email(principal), authorities 추출.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/me")
public class AdminMeController {

    record MeResponse(String email, String role, List<String> authorities) {}

    /**
     * 현재 인증된 사용자 정보 반환.
     * role 은 api:super-admin 권한 보유 시 "SUPER_ADMIN", 아니면 "ADMIN".
     */
    @GetMapping
    public ResponseEntity<MeResponse> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        String email = authentication.getName();
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        String role = authorities.contains("api:super-admin") ? "SUPER_ADMIN" : "ADMIN";

        return ResponseEntity.ok(new MeResponse(email, role, authorities));
    }
}
