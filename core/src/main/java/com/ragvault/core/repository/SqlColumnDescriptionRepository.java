package com.ragvault.core.repository;

import com.ragvault.core.domain.SqlColumnDescription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 컬럼 설명 리포지토리 (표준 JPA — pgvector 없음).
 */
public interface SqlColumnDescriptionRepository extends JpaRepository<SqlColumnDescription, Long> {

    List<SqlColumnDescription> findByDatasourceId(Integer datasourceId);

    List<SqlColumnDescription> findByDatasourceIdAndSourceTable(Integer datasourceId, String sourceTable);

    Optional<SqlColumnDescription> findByDatasourceIdAndSourceTableAndColumnName(
            Integer datasourceId, String sourceTable, String columnName);

    @Transactional
    void deleteByDatasourceIdAndSourceTable(Integer datasourceId, String sourceTable);
}
