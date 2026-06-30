package com.ragvault.widget.repository;

import com.ragvault.widget.domain.SiteKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SiteKeyRepository extends JpaRepository<SiteKey, Long> {

    Optional<SiteKey> findBySiteKey(String siteKey);

    List<SiteKey> findAllByOrderByCreatedAtDesc();

    Optional<SiteKey> findBySiteKeyAndActiveTrue(String siteKey);
}
