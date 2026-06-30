package com.ragvault.core.repository;

import com.ragvault.core.domain.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * DocumentChunk JPA 리포지토리.
 *
 * pgvector 코사인 유사도 검색은 JSqlParserQueryEnhancer 파싱 한계로
 * DocumentChunkRepositoryCustom(EntityManager 직접 사용)에 위임한다.
 * LL-0006 참조.
 *
 * M2: id 타입을 UUID → Long(BIGSERIAL)으로 변경.
 */
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long>, DocumentChunkRepositoryCustom {
    // pgvector 검색: findSimilarChunks() → DocumentChunkRepositoryImpl
    // upsertChunk(), deleteBySourceTableAndSourceId() → DocumentChunkRepositoryImpl
}
