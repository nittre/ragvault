package com.ragservice.rag.repository;

import com.ragservice.rag.domain.BinlogPosition;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * binlog_position 리포지토리 (싱글톤, id=1).
 *
 * ADR-0001: GTID 기반 binlog 위치 추적.
 */
public interface BinlogPositionRepository extends JpaRepository<BinlogPosition, Integer> {
}
