package com.ragservice.rag.repository;

import com.ragservice.rag.domain.RagTableConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * rag_table_config 리포지토리.
 */
public interface RagTableConfigRepository extends JpaRepository<RagTableConfig, Integer> {

    Optional<RagTableConfig> findBySourceTable(String sourceTable);

    Optional<RagTableConfig> findBySourceTableAndIsActiveTrue(String sourceTable);

    List<RagTableConfig> findAllByIsActiveTrue();
}
