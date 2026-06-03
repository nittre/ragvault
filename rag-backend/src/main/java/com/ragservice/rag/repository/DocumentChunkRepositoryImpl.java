package com.ragservice.rag.repository;

import com.ragservice.rag.domain.DocumentChunk;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * pgvector 코사인 유사도 검색 + 청크 UPSERT/DELETE — EntityManager 직접 사용.
 *
 * Spring Data JPA의 @Query(nativeQuery=true)는 JSqlParserQueryEnhancer를 통해
 * 쿼리를 파싱하는데, pgvector 전용 연산자(<=> 코사인 거리, CAST(... AS vector),
 * && 배열 연산자)를 지원하지 않아 ParseException이 발생한다.
 *
 * EntityManager.createNativeQuery()는 JSqlParser를 거치지 않으므로
 * PostgreSQL/pgvector 전용 SQL 문법을 자유롭게 사용할 수 있다.
 *
 * @Repository 어노테이션 제거: Spring Data JPA가 명명 규칙
 * (DocumentChunkRepository + "Impl")으로 자동 탐지한다.
 * @Repository를 붙이면 standalone 빈과 fragment 구현 모두로 등록되어
 * BeanNotOfRequiredTypeException이 발생한다.
 *
 * LL-0006 참조
 */
public class DocumentChunkRepositoryImpl implements DocumentChunkRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * pgvector 코사인 유사도 검색.
     *
     * SQL 설명:
     *   - CAST(:embedding AS vector): JSON 배열 문자열을 pgvector 타입으로 변환
     *   - <=> : pgvector 코사인 거리 연산자 (0=동일, 2=반대)
     *   - (embedding <=> ...) < (1 - threshold): 거리 임계값 필터
     *     (cosine_distance = 1 - cosine_similarity)
     *   - access_groups && ARRAY['all']: Phase 0 그룹 필터 (ADR-0002)
     *
     * @param embeddingJson "[0.1, 0.2, ...]" 형식의 JSON 배열 문자열
     * @param threshold     코사인 유사도 최소값 (0.0~1.0, 기본 0.65)
     * @param topK          반환할 최대 청크 수 (기본 5)
     * @return Object[] 리스트: [content, source_table, source_id, score]
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<Object[]> findSimilarChunks(String embeddingJson, double threshold, int topK) {
        String sql = """
                SELECT content, source_table, source_id,
                       CAST(1 - (embedding <=> CAST(:embedding AS vector)) AS double precision) AS score
                FROM document_chunks
                WHERE (embedding <=> CAST(:embedding AS vector)) < CAST(:maxDistance AS double precision)
                  AND access_groups && ARRAY['all']
                ORDER BY embedding <=> CAST(:embedding AS vector)
                LIMIT :topK
                """;

        Query query = entityManager.createNativeQuery(sql)
                .setParameter("embedding", embeddingJson)
                .setParameter("maxDistance", 1.0 - threshold)
                .setParameter("topK", topK);

        return query.getResultList();
    }

    /**
     * 청크 UPSERT — content_hash가 변경된 경우에만 업데이트 (ADR-0001 멱등성).
     *
     * ON CONFLICT: (source_table, source_id, chunk_index, embedding_model) UNIQUE 조건.
     * WHERE content_hash != EXCLUDED.content_hash: 내용이 같으면 업데이트 스킵.
     */
    @Override
    @Transactional
    public void upsertChunk(DocumentChunk chunk, float[] embedding) {
        String sql = """
                INSERT INTO document_chunks
                    (source_table, source_id, source_type, chunk_index, content, content_hash,
                     token_count, embedding, embedding_model, tokenizer_model, metadata, access_groups,
                     created_at, updated_at)
                VALUES
                    (:sourceTable, :sourceId, :sourceType, :chunkIndex, :content, :contentHash,
                     :tokenCount, CAST(:embedding AS vector), :embeddingModel, :tokenizerModel,
                     CAST(:metadata AS jsonb), ARRAY['all'], NOW(), NOW())
                ON CONFLICT (source_table, source_id, chunk_index, embedding_model)
                DO UPDATE SET
                    content = EXCLUDED.content,
                    content_hash = EXCLUDED.content_hash,
                    token_count = EXCLUDED.token_count,
                    embedding = EXCLUDED.embedding,
                    metadata = EXCLUDED.metadata,
                    updated_at = NOW()
                WHERE document_chunks.content_hash != EXCLUDED.content_hash
                """;

        entityManager.createNativeQuery(sql)
                .setParameter("sourceTable", chunk.getSourceTable())
                .setParameter("sourceId", chunk.getSourceId())
                .setParameter("sourceType", chunk.getSourceType())
                .setParameter("chunkIndex", chunk.getChunkIndex())
                .setParameter("content", chunk.getContent())
                .setParameter("contentHash", chunk.getContentHash())
                .setParameter("tokenCount", chunk.getTokenCount())
                .setParameter("embedding", toJsonArray(embedding))
                .setParameter("embeddingModel", chunk.getEmbeddingModel())
                .setParameter("tokenizerModel", chunk.getTokenizerModel())
                .setParameter("metadata", chunk.getMetadata())
                .executeUpdate();
    }

    /**
     * 특정 소스 테이블 + 소스 ID의 모든 청크 삭제.
     */
    @Override
    @Transactional
    public void deleteBySourceTableAndSourceId(String sourceTable, String sourceId) {
        entityManager.createNativeQuery(
                "DELETE FROM document_chunks WHERE source_table = :t AND source_id = :id")
                .setParameter("t", sourceTable)
                .setParameter("id", sourceId)
                .executeUpdate();
    }

    /**
     * float[] → pgvector JSON 배열 문자열 변환 "[v1,v2,...]".
     */
    private String toJsonArray(float[] embedding) {
        if (embedding == null || embedding.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
