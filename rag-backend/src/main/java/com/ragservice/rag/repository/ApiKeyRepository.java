package com.ragservice.rag.repository;

import com.ragservice.rag.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /**
     * key_prefix로 활성 API 키 후보를 조회한다.
     * bcrypt 검증은 서비스 레이어에서 수행.
     */
    @Query("SELECT k FROM ApiKey k WHERE k.keyPrefix = :prefix AND k.isActive = true AND k.expiresAt > :now")
    List<ApiKey> findActiveByPrefix(@Param("prefix") String prefix, @Param("now") Instant now);
}
