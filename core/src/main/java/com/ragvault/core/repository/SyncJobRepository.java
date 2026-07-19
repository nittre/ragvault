package com.ragvault.core.repository;

import com.ragvault.core.domain.SyncJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * sync_jobs 리포지토리.
 */
public interface SyncJobRepository extends JpaRepository<SyncJob, UUID> {

    Optional<SyncJob> findTopByOrderByStartedAtDesc();

    List<SyncJob> findByStatusOrderByStartedAtDesc(String status);
}
