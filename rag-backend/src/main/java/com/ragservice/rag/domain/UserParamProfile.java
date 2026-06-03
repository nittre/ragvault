package com.ragservice.rag.domain;

import com.ragservice.rag.domain.converter.JsonMapConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * user_param_profiles 엔티티.
 *
 * 사용자가 파라미터 패널에서 '💾 프로필 저장'한 값을 보관.
 * 7단계 우선순위 체인의 Stage 4 (ADR-0005).
 *
 * params 필드는 Map<String, Object> ↔ JSONB. 변경된 파라미터만 저장.
 * 키 목록: requirements/09-user-parameter-tuning.md 섹션 2 참조.
 */
@Entity
@Table(name = "user_param_profiles")
@Getter
@Setter
@NoArgsConstructor
public class UserParamProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_email", unique = true, nullable = false, length = 200)
    private String userEmail;

    /**
     * 사용자가 저장한 파라미터 맵. 없는 키는 상위 Stage에서 채운다.
     * Map 초기화: null 방지 (DB DEFAULT '{}'와 일치).
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> params = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
        if (params == null) {
            params = new HashMap<>();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
