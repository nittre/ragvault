package com.ragvault.core.repository;

import com.ragvault.core.domain.BinlogEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * binlog_events 리포지토리.
 */
public interface BinlogEventRepository extends JpaRepository<BinlogEvent, Long> {

    List<BinlogEvent> findByProcessedFalseOrderByCreatedAtAsc();
}
