package com.ragservice.rag.repository;

import java.util.List;

/**
 * 백과사전(business_knowledge) pgvector 네이티브 쿼리 커스텀 리포지토리.
 *
 * LL-0006: pgvector 전용 연산자(<=>, CAST(... AS vector))는 JSqlParserQueryEnhancer 가
 * 파싱하지 못하므로 EntityManager 를 직접 사용한다. @Query(nativeQuery=true) 금지.
 */
public interface BusinessKnowledgeRepositoryCustom {

    /** 임베딩 포함 지식 행. reindex 시 INSERT 용. */
    record KnowledgeRow(String title, String content, String knowledgeRole,
                        boolean pinned, float[] embedding) {}

    /**
     * 특정 데이터소스의 지식을 전부 삭제 후 재삽입.
     */
    void replaceForDatasource(Integer datasourceId, List<KnowledgeRow> rows);

    /**
     * pinned(pinned=true) 지식 — 항상 주입.
     * @return Object[] 리스트: [title, content, knowledge_role]
     */
    List<Object[]> findPinned(Integer datasourceId);

    /**
     * 동적 지식 코사인 유사도 검색 (pinned=false).
     * @param embeddingJson "[0.1, 0.2, ...]" JSON 배열 문자열
     * @return Object[] 리스트: [title, content, knowledge_role, score]
     */
    List<Object[]> searchDynamic(String embeddingJson, Integer datasourceId,
                                 double threshold, int topK);
}
