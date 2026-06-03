package com.ragservice.rag.repository;

import com.ragservice.rag.domain.DdlEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * ddl_events 리포지토리.
 */
public interface DdlEventRepository extends JpaRepository<DdlEvent, Long> {

    List<DdlEvent> findByProcessedAtIsNullOrderByCreatedAtDesc();

    @Query("SELECT d FROM DdlEvent d WHERE d.riskLevel = 'MEDIUM' AND d.processedAt IS NULL AND d.autoApplyAt < :now")
    List<DdlEvent> findMediumEventsReadyForAutoApply(@Param("now") Instant now);
}
