package com.ragvault.core.repository;

import com.ragvault.core.domain.RagUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RagUserRepository extends JpaRepository<RagUser, UUID> {

    Optional<RagUser> findByEmailAndActiveTrue(String email);

    List<RagUser> findAllByOrderByCreatedAtDesc();

    boolean existsByEmail(String email);
}
