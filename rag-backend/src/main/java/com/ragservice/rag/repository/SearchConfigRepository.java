package com.ragservice.rag.repository;

import com.ragservice.rag.domain.SearchConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SearchConfigRepository extends JpaRepository<SearchConfig, UUID> {
    Optional<SearchConfig> findByConfigKey(String configKey);
}
