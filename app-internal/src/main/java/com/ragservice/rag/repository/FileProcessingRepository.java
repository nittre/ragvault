package com.ragservice.rag.repository;

import com.ragservice.rag.domain.FileProcessing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileProcessingRepository extends JpaRepository<FileProcessing, UUID> {
    Optional<FileProcessing> findByIdAndUserEmail(UUID id, String userEmail);
    List<FileProcessing> findByExpiresAtBefore(LocalDateTime now);
}
