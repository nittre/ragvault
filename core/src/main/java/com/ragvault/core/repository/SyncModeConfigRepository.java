package com.ragvault.core.repository;

import com.ragvault.core.domain.SyncModeConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SyncModeConfigRepository extends JpaRepository<SyncModeConfig, Long> {
    Optional<SyncModeConfig> findByDatasourceIdAndTableType(Integer datasourceId, String tableType);
}
