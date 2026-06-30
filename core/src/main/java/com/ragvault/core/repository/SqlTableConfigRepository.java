package com.ragvault.core.repository;

import com.ragvault.core.domain.SqlTableConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * sql_table_config 리포지토리.
 * SQL 경로 화이트리스트 테이블 조회 (ADR-0007 Layer 1).
 * 멀티 데이터소스: datasource_id 별 활성 테이블 조회 지원.
 */
public interface SqlTableConfigRepository extends JpaRepository<SqlTableConfig, Integer> {

    List<SqlTableConfig> findByIsActiveTrue();

    Optional<SqlTableConfig> findBySourceTableAndIsActiveTrue(String sourceTable);

    List<SqlTableConfig> findAllByOrderByIdAsc();

    Optional<SqlTableConfig> findBySourceTableAndDatasourceId(String sourceTable, Integer datasourceId);

    List<SqlTableConfig> findByDatasourceIdOrderByIdAsc(Integer datasourceId);

    List<SqlTableConfig> findByDatasourceIdAndIsActiveTrue(Integer datasourceId);

    List<SqlTableConfig> findByDatasourceIdIsNullAndIsActiveTrue();

    @Modifying
    @Transactional
    @Query("DELETE FROM SqlTableConfig s WHERE s.sourceTable = :sourceTable AND s.datasourceId = :datasourceId")
    int deleteBySourceTableAndDatasourceId(@Param("sourceTable") String sourceTable, @Param("datasourceId") Integer datasourceId);
}
