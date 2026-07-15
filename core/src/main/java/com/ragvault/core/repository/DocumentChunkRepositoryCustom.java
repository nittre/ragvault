package com.ragvault.core.repository;

import com.ragvault.core.domain.DocumentChunk;

import java.util.List;

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
     * - embedding: "[0.1, 0.2, ...]" JSON 배열 문자열
     * - threshold: 코사인 유사도 최소값 (similarity >= threshold)
     * - topK: 반환할 최대 청크 수
     * - datasourceId: 검색을 이 데이터소스로 격리. 이 값을 넘겨도 datasource_id가 NULL인
     *   청크(지식문서 등 특정 데이터소스에 속하지 않는 전역 콘텐츠)는 항상 결과에 포함된다 —
     *   테이블 기반 RAG 데이터만 데이터소스별로 격리되고, 전역 지식문서는 모든 검색에 노출됨.
     *   null을 넘기면 격리 없이 전체 검색(관리자/툴 전용 검색 엔드포인트용).
     *
     * @return Object[] 리스트: [content(String), source_table(String), source_id(String), score(Double)]
     */
    List<Object[]> findSimilarChunks(String embeddingJson, double threshold, int topK, Integer datasourceId);

    /**
     * 청크 UPSERT — content_hash가 변경된 경우에만 업데이트 (ADR-0001 멱등성).
     *
     * @param chunk     DocumentChunk 엔티티 (datasourceId 포함)
     * @param embedding 임베딩 벡터
     */
    void upsertChunk(DocumentChunk chunk, float[] embedding);

    /**
     * 특정 데이터소스의 소스 테이블 + 소스 ID의 모든 청크 삭제.
     *
     * @param datasourceId 데이터소스 ID
     * @param sourceTable  소스 테이블명
     * @param sourceId     소스 레코드 PK
     */
    void deleteBySourceTableAndSourceId(Integer datasourceId, String sourceTable, String sourceId);

    /**
     * 특정 데이터소스의 source_table 전체 청크 삭제 — DB 동기화 재적재 멱등성.
     */
    void deleteBySourceTable(Integer datasourceId, String sourceTable);
}
