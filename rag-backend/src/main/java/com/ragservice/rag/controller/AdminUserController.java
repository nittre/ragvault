package com.ragservice.rag.controller;

import com.ragservice.rag.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

/**
 * 사용자 관리 Admin API.
 * A2 시나리오: 검색·필터·정렬·CSV batch upload
 *
 * Phase 0: 사용자 = api_keys 테이블 기반 (Open WebUI 사용자 DB 직접 접근 불가)
 * 접근 권한: api:admin scope (SecurityConfig 에서 강제)
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final ApiKeyRepository apiKeyRepository;

    /** 사용자 목록 조회 (이메일 검색·페이징) */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String email,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = apiKeyRepository.findAll().stream()
                .filter(k -> email == null || (k.getCreatedBy() != null && k.getCreatedBy().contains(email)))
                .skip((long) page * size)
                .limit(size)
                .map(k -> Map.of(
                        "id", k.getId().toString(),
                        "email", k.getCreatedBy() != null ? k.getCreatedBy() : "",
                        "keyPrefix", k.getKeyPrefix(),
                        "scope", k.getScopes(),
                        "active", k.isActive(),
                        "createdAt", k.getCreatedAt() != null ? k.getCreatedAt().toString() : ""
                ))
                .toList();
        return ResponseEntity.ok(Map.of("data", result, "page", page, "size", size));
    }

    /**
     * CSV batch upload.
     * 파일 형식: email,scope (헤더 행 포함)
     * Phase 0: 큐잉만 — 실제 발급은 AdminApiKeyController 에서 수행
     */
    @PostMapping("/csv")
    public ResponseEntity<?> uploadCsv(@RequestParam("file") MultipartFile file) {
        List<Map<String, Object>> results = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream()))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; } // 헤더 skip
                String[] parts = line.split(",");
                if (parts.length < 1) continue;
                String emailVal = parts[0].trim();
                String scope = parts.length > 1 ? parts[1].trim() : "api:chat";
                results.add(Map.of("email", emailVal, "scope", scope, "status", "queued"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("CSV 파싱 실패: " + e.getMessage());
        }
        return ResponseEntity.ok(Map.of("queued", results.size(), "rows", results));
    }
}
