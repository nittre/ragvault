package com.ragservice.rag.controller;

import com.ragservice.rag.domain.ApiKey;
import com.ragservice.rag.repository.ApiKeyRepository;
import com.ragservice.rag.runner.ApiKeyBootstrapRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * API Key 발급·회전·폐기 Admin API.
 * A9 시나리오
 *
 * 접근 권한: api:admin scope (SecurityConfig 에서 강제)
 * raw key 는 응답에서 1회만 노출, 저장하지 않음 (bcrypt hash 만 저장)
 */
@RestController
@RequestMapping("/api/v1/admin/api-keys")
@RequiredArgsConstructor
public class AdminApiKeyController {

    private final ApiKeyRepository apiKeyRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    /** API Key 목록 조회 */
    @GetMapping
    public ResponseEntity<?> list() {
        var keys = apiKeyRepository.findAll().stream()
                .map(k -> Map.of(
                        "id", k.getId().toString(),
                        "email", k.getCreatedBy() != null ? k.getCreatedBy() : "",
                        "keyPrefix", k.getKeyPrefix(),
                        "scope", k.getScopes(),
                        "active", k.isActive(),
                        "createdAt", k.getCreatedAt() != null ? k.getCreatedAt().toString() : ""
                ))
                .toList();
        return ResponseEntity.ok(keys);
    }

    /**
     * 신규 API Key 발급.
     * raw key 는 응답에서 1회만 노출됨 (이후 재조회 불가).
     */
    @PostMapping
    public ResponseEntity<?> issue(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String scope = body.getOrDefault("scope", "api:chat");
        String name = body.getOrDefault("name", email);
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email 필수"));
        }

        String rawKey = "rk_" + UUID.randomUUID().toString().replace("-", "");
        // key_prefix: ApiKeyAuthFilter.KEY_PREFIX_LENGTH(15자) 와 동일하게 유지
        String prefix = rawKey.substring(0, ApiKeyBootstrapRunner.PREFIX_LENGTH);

        ApiKey key = new ApiKey();
        key.setName(name);
        key.setKeyPrefix(prefix);
        key.setKeyHash(passwordEncoder.encode(rawKey));
        key.setScopes(scope);
        key.setActive(true);
        key.setCreatedBy(email);
        key.setCreatedAt(Instant.now());
        key.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        apiKeyRepository.save(key);

        return ResponseEntity.ok(Map.of(
                "id", key.getId().toString(),
                "email", email,
                "key", rawKey,       // 발급 시 1회만 노출
                "keyPrefix", prefix,
                "scope", scope
        ));
    }

    /** API Key 폐기 (soft delete) */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> revoke(@PathVariable UUID id) {
        return apiKeyRepository.findById(id).map(k -> {
            k.setActive(false);
            k.setDeactivatedAt(Instant.now());
            apiKeyRepository.save(k);
            return ResponseEntity.ok(Map.of("revoked", true, "id", id.toString()));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * API Key 회전 — 새 키로 교체, 기존 키 무효화.
     * raw key 는 응답에서 1회만 노출됨.
     */
    @PostMapping("/{id}/rotate")
    public ResponseEntity<?> rotate(@PathVariable UUID id) {
        return apiKeyRepository.findById(id).map(k -> {
            String rawKey = "rk_" + UUID.randomUUID().toString().replace("-", "");
            String prefix = rawKey.substring(0, ApiKeyBootstrapRunner.PREFIX_LENGTH);
            k.setKeyPrefix(prefix);
            k.setKeyHash(passwordEncoder.encode(rawKey));
            k.setActive(true);
            k.setDeactivatedAt(null);
            k.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
            apiKeyRepository.save(k);
            return ResponseEntity.ok(Map.of(
                    "id", id.toString(),
                    "key", rawKey,
                    "keyPrefix", prefix
            ));
        }).orElse(ResponseEntity.notFound().build());
    }
}
