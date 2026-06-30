package com.ragservice.rag.repository;

import com.ragservice.rag.domain.SyncModeConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SyncModeConfigRepository extends JpaRepository<SyncModeConfig, Long> {
    Optional<SyncModeConfig> findByDatasourceIdAndTableType(Integer datasourceId, String tableType);
}
