package com.ragservice.rag.repository;

import java.util.List;

import com.ragservice.rag.domain.DocumentChunk;

/**
 * pgvector 네이티브 쿼리 커스텀 리포지토리 인터페이스.
 *
 * Spring Data JPA의 JSqlParserQueryEnhancer는 pgvector 전용 연산자
 * (<=> 코사인 거리, CAST(... AS vector), && 배열 연산자)를 파싱하지 못한다.
 * @Query(nativeQuery=true) 대신 EntityManager를 직접 사용한다.
 *
 * LL-0006 참조: pgvector native query 파싱 실패 해결책
 */
public interface DocumentChunkRepositoryCustom {

    /**
     * pgvector 코사인 유사도 검색.
     *
     * - access_groups && ARRAY['all'] 필터 적용 (ADR-0002, Phase 0 고정)
     * - embedding: "[0.1, 0.2, ...]" JSON 배열 문자열
     * - threshold: 코사인 유사도 최소값 (similarity >= threshold)
     * - topK: 반환할 최대 청크 수
     *
     * @return Object[] 리스트: [content(String), source_table(String), source_id(String), score(Double)]
     */
    List<Object[]> findSimilarChunks(String embeddingJson, double threshold, int topK);

    /**
     * 청크 UPSERT — content_hash가 변경된 경우에만 업데이트 (ADR-0001 멱등성).
     *
     * @param chunk     DocumentChunk 엔티티
     * @param embedding 임베딩 벡터
     */
    void upsertChunk(DocumentChunk chunk, float[] embedding);

    /**
     * 특정 소스 테이블 + 소스 ID의 모든 청크 삭제.
     *
     * @param sourceTable 소스 테이블명
     * @param sourceId    소스 레코드 PK
     */
    void deleteBySourceTableAndSourceId(String sourceTable, String sourceId);
}
