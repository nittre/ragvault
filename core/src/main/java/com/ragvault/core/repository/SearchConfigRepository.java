package com.ragvault.core.repository;

import com.ragvault.core.domain.SearchConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SearchConfigRepository extends JpaRepository<SearchConfig, UUID> {
    Optional<SearchConfig> findByConfigKey(String configKey);
}
