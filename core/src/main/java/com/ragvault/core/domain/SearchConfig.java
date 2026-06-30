package com.ragvault.core.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "search_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "config_key", unique = true, nullable = false)
    private String configKey;

    @Column(name = "config_value", nullable = false)
    private String configValue;

    private String description;

    private String updatedBy;

    private LocalDateTime updatedAt;
}
