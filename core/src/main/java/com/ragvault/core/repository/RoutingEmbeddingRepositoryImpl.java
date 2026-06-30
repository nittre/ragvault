package com.ragvault.core.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * routing_embedding pgvector 네이티브 쿼리 구현.
 *
 * EntityManager.createNativeQuery() 사용 — pgvector 전용 문법 JSqlParser 우회 (LL-0006).
 * @Repository 미부착: 명명규칙(...RepositoryImpl) 자동탐지.
 */
public class RoutingEmbeddingRepositoryImpl implements RoutingEmbeddingRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void replaceForDatasource(Integer datasourceId, List<EmbRow> rows) {
        entityManager.createNativeQuery(
                "DELETE FROM routing_embedding WHERE datasource_id = :dsId")
                .setParameter("dsId", datasourceId)
                .executeUpdate();

        String insert = """
                INSERT INTO routing_embedding
                    (datasource_id, source_table, content, embedding, updated_at)
                VALUES
                    (:dsId, :st, :content, CAST(:emb AS vector), NOW())
                """;
        for (EmbRow r : rows) {
            entityManager.createNativeQuery(insert)
                    .setParameter("dsId", datasourceId)
                    .setParameter("st", r.sourceTable())
                    .setParameter("content", r.content())
                    .setParameter("emb", toJsonArray(r.embedding()))
                    .executeUpdate();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Integer> searchCandidateDatasourceIds(String embeddingJson, int topK) {
        // 유사한 행 topK 를 거리순으로 뽑고, datasource_id 의 최소 거리 순으로 distinct 반환.
        String sql = """
                SELECT datasource_id
                FROM (
                    SELECT datasource_id, MIN(embedding <=> CAST(:emb AS vector)) AS dist
                    FROM routing_embedding
                    WHERE embedding IS NOT NULL
                    GROUP BY datasource_id
                ) t
                ORDER BY t.dist
                LIMIT :topK
                """;
        List<Number> ids = entityManager.createNativeQuery(sql)
                .setParameter("emb", embeddingJson)
                .setParameter("topK", topK)
                .getResultList();
        return ids.stream().map(Number::intValue).toList();
    }

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
