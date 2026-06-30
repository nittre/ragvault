package com.ragvault.core.repository;

import com.ragvault.core.domain.RagTableConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * rag_table_config 리포지토리.
 */
public interface RagTableConfigRepository extends JpaRepository<RagTableConfig, Integer> {

    Optional<RagTableConfig> findBySourceTable(String sourceTable);

    Optional<RagTableConfig> findBySourceTableAndIsActiveTrue(String sourceTable);

    Optional<RagTableConfig> findBySourceTableAndDatasourceId(String sourceTable, Integer datasourceId);

    List<RagTableConfig> findAllByIsActiveTrue();

    List<RagTableConfig> findByDatasourceIdOrderByIdAsc(Integer datasourceId);

    List<RagTableConfig> findByDatasourceIdAndIsActiveTrue(Integer datasourceId);

    @Modifying
    @Transactional
    @Query("DELETE FROM RagTableConfig r WHERE r.sourceTable = :sourceTable AND r.datasourceId = :datasourceId")
    int deleteBySourceTableAndDatasourceId(@Param("sourceTable") String sourceTable, @Param("datasourceId") Integer datasourceId);
}
