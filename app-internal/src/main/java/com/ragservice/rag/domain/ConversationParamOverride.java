package com.ragservice.rag.domain;

import com.ragservice.rag.domain.converter.JsonMapConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * conversation_param_overrides 엔티티.
 *
 * 사용자가 파라미터 패널에서 '📋 대화별만' 선택한 값을 보관.
 * 7단계 우선순위 체인의 Stage 5 (ADR-0005).
 *
 * (conversation_id, user_email) 복합 유니크 — 대화 1개당 사용자 1행.
 */
@Entity
@Table(
    name = "conversation_param_overrides",
    uniqueConstraints = @UniqueConstraint(columnNames = {"conversation_id", "user_email"})
)
@Getter
@Setter
@NoArgsConstructor
public class ConversationParamOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false, length = 200)
    private String conversationId;

    @Column(name = "user_email", nullable = false, length = 200)
    private String userEmail;

    /**
     * 이 대화에서만 적용할 파라미터 맵. 프로필과 다른 키만 넣어도 됨.
     * DB DEFAULT '{}' 와 일치하도록 초기화.
     */
    @Convert(converter = JsonMapConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
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
