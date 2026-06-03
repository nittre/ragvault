package com.ragservice.rag.repository;

import com.ragservice.rag.domain.SqlTableConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * sql_table_config 리포지토리.
 * SQL 경로 화이트리스트 테이블 조회 (ADR-0007 Layer 1).
 */
public interface SqlTableConfigRepository extends JpaRepository<SqlTableConfig, Integer> {

    List<SqlTableConfig> findByIsActiveTrue();

    Optional<SqlTableConfig> findBySourceTableAndIsActiveTrue(String sourceTable);

    List<SqlTableConfig> findAllByOrderByIdAsc();
}
