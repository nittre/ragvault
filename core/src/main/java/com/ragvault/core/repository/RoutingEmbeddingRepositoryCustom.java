package com.ragvault.core.repository;

import java.util.List;

/**
 * routing_embedding pgvector 네이티브 쿼리 커스텀 리포지토리 (LL-0006).
 */
public interface RoutingEmbeddingRepositoryCustom {

    /** 임베딩 포함 행. reindex 시 INSERT 용. */
    record EmbRow(String sourceTable, String content, float[] embedding) {}

    /** 특정 데이터소스의 라우팅 임베딩을 전부 삭제 후 재삽입. */
    void replaceForDatasource(Integer datasourceId, List<EmbRow> rows);

    /**
     * 질문 임베딩과 유사한 라우팅 후보 datasource_id 목록 (거리 오름차순, 중복 제거).
     * @param embeddingJson "[0.1, ...]" JSON 배열
     */
    List<Integer> searchCandidateDatasourceIds(String embeddingJson, int topK);
}
