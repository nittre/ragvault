package com.ragservice.rag.controller;

import com.ragservice.rag.domain.ApiKey;
import com.ragservice.rag.repository.ApiKeyRepository;
import com.ragservice.rag.runner.ApiKeyBootstrapRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
                .map(k -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", k.getId().toString());
                    row.put("name", k.getName());
                    row.put("email", k.getCreatedBy() != null ? k.getCreatedBy() : "");
                    row.put("keyPrefix", k.getKeyPrefix());
                    row.put("scopes", k.getScopes());
                    row.put("active", k.isActive());
                    row.put("expiresAt", k.getExpiresAt() != null ? k.getExpiresAt().toString() : "");
                    row.put("lastUsedAt", k.getLastUsedAt() != null ? k.getLastUsedAt().toString() : "");
                    row.put("createdAt", k.getCreatedAt() != null ? k.getCreatedAt().toString() : "");
                    return row;
                })
                .toList();
        return ResponseEntity.ok(keys);
    }

    /**
     * 신규 API Key 발급.
     * raw key 는 응답에서 1회만 노출됨 (이후 재조회 불가).
     */
    @PostMapping
    public ResponseEntity<?> issue(@RequestBody Map<String, String> body, Authentication authentication) {
        String email = authentication != null ? authentication.getName() : null;
        String scope = body.getOrDefault("scopes", "api:chat");
        String name = body.getOrDefault("name", email);
        String expiresAtRaw = body.get("expiresAt");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "인증 정보에서 email 을 확인할 수 없습니다."));
        }
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name 필수"));
        }

        Instant expiresAt;
        if (expiresAtRaw == null || expiresAtRaw.isBlank()) {
            expiresAt = Instant.now().plus(365, ChronoUnit.DAYS);
        } else {
            try {
                expiresAt = LocalDate.parse(expiresAtRaw).atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "expiresAt 형식이 올바르지 않습니다. (yyyy-MM-dd)"));
            }
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
        key.setExpiresAt(expiresAt);
        apiKeyRepository.save(key);

        return ResponseEntity.ok(Map.of(
                "id", key.getId().toString(),
                "name", name,
                "email", email,
                "rawKey", rawKey,       // 발급 시 1회만 노출
                "keyPrefix", prefix,
                "scopes", scope,
                "active", true,
                "expiresAt", expiresAt.toString(),
                "createdAt", key.getCreatedAt().toString()
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
                    "rawKey", rawKey,
                    "keyPrefix", prefix
            ));
        }).orElse(ResponseEntity.notFound().build());
    }
}
