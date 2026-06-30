package com.ragservice.rag.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
@Getter
@Setter
@NoArgsConstructor
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "key_hash", nullable = false, unique = true, length = 255)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 20)
    private String keyPrefix;

    /**
     * comma-separated scopes, e.g. "api:chat,api:admin"
     * ADR-0002: Phase 0 단순 구조 — TEXT 타입으로 저장
     */
    @Column(nullable = false)
    private String scopes = "api:chat";

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_by", length = 200)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    /**
     * scopes를 List로 파싱하여 반환한다.
     */
    public List<String> getScopeList() {
        if (scopes == null || scopes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(scopes.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }
}
