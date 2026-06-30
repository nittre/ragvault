package com.ragservice.rag.repository;

import com.ragservice.rag.domain.BusinessKnowledge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 백과사전(business_knowledge) 리포지토리.
 * 표준 CRUD(JpaRepository) + pgvector 네이티브 쿼리(Custom) 결합.
 */
public interface BusinessKnowledgeRepository
        extends JpaRepository<BusinessKnowledge, Long>, BusinessKnowledgeRepositoryCustom {

    List<BusinessKnowledge> findByDatasourceIdOrderByIdAsc(Integer datasourceId);
}
