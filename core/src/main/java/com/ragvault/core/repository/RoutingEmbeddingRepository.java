package com.ragvault.core.repository;

import com.ragvault.core.domain.RoutingEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * routing_embedding 리포지토리. 표준 CRUD + pgvector 네이티브 쿼리(Custom).
 */
public interface RoutingEmbeddingRepository
        extends JpaRepository<RoutingEmbedding, Long>, RoutingEmbeddingRepositoryCustom {
}
