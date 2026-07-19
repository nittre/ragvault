package com.ragvault.core.repository;

import com.ragvault.core.domain.BinlogPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * binlog_position 리포지토리 (싱글톤, id=1).
 *
 * ADR-0001: GTID 기반 binlog 위치 추적.
 * 멀티 데이터소스: datasource_id 별로 독립적인 GTID 위치 관리.
 * datasource_id = null → legacy rag.mysql.* (기존 id=1 레코드 호환).
 */
public interface BinlogPositionRepository extends JpaRepository<BinlogPosition, Integer> {

    Optional<BinlogPosition> findByDatasourceId(Integer datasourceId);

    Optional<BinlogPosition> findByDatasourceIdIsNull();
}
