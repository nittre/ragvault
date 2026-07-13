package com.ragvault.widget.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 대화 로그 엔티티.
 *
 * isBlocked 필드: Lombok @Getter 가 boolean 필드 isXxx 를 isXxx() 로 생성하면
 * Jackson 이 JSON 직렬화 시 "blocked" 로 매핑한다.
 * @Column(name="is_blocked") 명시로 DB 컬럼명 보장 (LL-0005 참고).
 */
@Entity
@Table(name = "conversation_logs")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    @Column(name = "site_key", length = 100)
    private String siteKey;

    @Column(name = "user_message", nullable = false, columnDefinition = "TEXT")
    private String userMessage;

    @Column(name = "bot_response", nullable = false, columnDefinition = "TEXT")
    private String botResponse;

    @Column(name = "is_blocked", nullable = false)
    private boolean isBlocked;

    @Column(name = "has_context", nullable = false)
    private boolean hasContext;

    @Column(name = "source_count", nullable = false)
    private int sourceCount;

    /** 라우팅 분류: RAG / SQL / HYBRID / REJECT / OTHER. */
    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
