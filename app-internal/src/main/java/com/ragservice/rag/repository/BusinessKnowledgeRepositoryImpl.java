package com.ragservice.rag.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 백과사전(business_knowledge) pgvector 네이티브 쿼리 구현.
 *
 * EntityManager.createNativeQuery() 사용 — pgvector 전용 문법(<=>, CAST(... AS vector))을
 * JSqlParser 우회 (LL-0006). @Repository 미부착: 명명규칙(...RepositoryImpl) 자동탐지.
 *
 * @see DocumentChunkRepositoryImpl 동일 패턴
 */
public class BusinessKnowledgeRepositoryImpl implements BusinessKnowledgeRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void replaceForDatasource(Integer datasourceId, List<KnowledgeRow> rows) {
        entityManager.createNativeQuery(
                "DELETE FROM business_knowledge WHERE datasource_id = :dsId")
                .setParameter("dsId", datasourceId)
                .executeUpdate();

        String insert = """
                INSERT INTO business_knowledge
                    (datasource_id, title, content, knowledge_role, pinned, embedding, created_at, updated_at)
                VALUES
                    (:dsId, :title, :content, :role, :pinned, CAST(:emb AS vector), NOW(), NOW())
                """;
        for (KnowledgeRow r : rows) {
            entityManager.createNativeQuery(insert)
                    .setParameter("dsId", datasourceId)
                    .setParameter("title", r.title())
                    .setParameter("content", r.content())
                    .setParameter("role", r.knowledgeRole())
                    .setParameter("pinned", r.pinned())
                    .setParameter("emb", toJsonArray(r.embedding()))
                    .executeUpdate();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Object[]> findPinned(Integer datasourceId) {
        if (datasourceId == null) return List.of();
        return entityManager.createNativeQuery("""
                SELECT title, content, knowledge_role
                FROM business_knowledge
                WHERE pinned = true AND datasource_id = :dsId
                ORDER BY id
                """)
                .setParameter("dsId", datasourceId)
                .getResultList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Object[]> searchDynamic(String embeddingJson, Integer datasourceId,
                                        double threshold, int topK) {
        if (datasourceId == null) return List.of();
        String sql = """
                SELECT title, content, knowledge_role,
                       CAST(1 - (embedding <=> CAST(:emb AS vector)) AS double precision) AS score
                FROM business_knowledge
                WHERE pinned = false AND datasource_id = :dsId
                  AND embedding IS NOT NULL
                  AND (embedding <=> CAST(:emb AS vector)) < CAST(:maxDistance AS double precision)
                ORDER BY embedding <=> CAST(:emb AS vector)
                LIMIT :topK
                """;
        return entityManager.createNativeQuery(sql)
                .setParameter("emb", embeddingJson)
                .setParameter("maxDistance", 1.0 - threshold)
                .setParameter("dsId", datasourceId)
                .setParameter("topK", topK)
                .getResultList();
    }

    /** float[] → pgvector JSON 배열 문자열 "[v1,v2,...]". */
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
