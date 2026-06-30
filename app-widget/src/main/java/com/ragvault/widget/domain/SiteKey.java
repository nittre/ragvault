package com.ragvault.widget.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "site_keys")
@Getter
@Setter
@NoArgsConstructor
public class SiteKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_key", nullable = false, unique = true, length = 100)
    private String siteKey;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "brand_color", nullable = false, length = 20)
    private String brandColor = "#2563eb";

    @Column(name = "bot_name", nullable = false, length = 100)
    private String botName = "챗봇";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String greeting = "안녕하세요! 자주 묻는 질문이나 안내가 필요한 내용을 입력해 주세요.";

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "allowed_origins", columnDefinition = "TEXT")
    private String allowedOrigins;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
