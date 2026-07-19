package com.ragvault.core.repository;

import com.ragvault.core.domain.DdlEvent;
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

    List<DdlEvent> findByDatasourceIdOrderByCreatedAtDesc(Integer datasourceId);

    List<DdlEvent> findByDatasourceIdAndProcessedAtIsNullOrderByCreatedAtDesc(Integer datasourceId);

    List<DdlEvent> findByDatasourceIdAndCreatedAtAfterOrderByCreatedAtAsc(Integer datasourceId, Instant since);

    @Query("SELECT d FROM DdlEvent d WHERE d.riskLevel = 'MEDIUM' AND d.processedAt IS NULL AND d.autoApplyAt < :now")
    List<DdlEvent> findMediumEventsReadyForAutoApply(@Param("now") Instant now);
}
