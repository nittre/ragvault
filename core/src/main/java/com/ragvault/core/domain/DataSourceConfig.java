package com.ragvault.core.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * datasource_config 엔티티.
 *
 * 멀티 MySQL/MariaDB 데이터소스 등록·관리.
 * password_enc: AES-256-GCM 암호화 저장 (DataSourceEncryptionService).
 * datasource_id = null → legacy rag.mysql.* 환경변수 경로로 fallback.
 */
@Entity
@Table(name = "datasource_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DataSourceConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "db_type", nullable = false, length = 20)
    @Builder.Default
    private String dbType = "mysql";

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    @Builder.Default
    private int port = 3306;

    @Column(name = "db_name", nullable = false, length = 100)
    private String dbName;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "password_enc", nullable = false, columnDefinition = "TEXT")
    private String passwordEnc;

    @Builder.Default
    @Column(name = "is_active")
    private boolean isActive = true;

    @Builder.Default
    @Column(name = "is_internal")
    private boolean isInternal = false;

    // ── SSH 터널 설정 ─────────────────────────────────────────────────────────

    @Builder.Default
    @Column(name = "ssh_enabled")
    private boolean sshEnabled = false;

    @Column(name = "ssh_host")
    private String sshHost;

    @Builder.Default
    @Column(name = "ssh_port")
    private Integer sshPort = 22;

    @Column(name = "ssh_user")
    private String sshUser;

    @Column(name = "ssh_private_key_enc", columnDefinition = "TEXT")
    private String sshPrivateKeyEnc;

    @Column(name = "ssh_passphrase_enc", columnDefinition = "TEXT")
    private String sshPassphraseEnc;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
