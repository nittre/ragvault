package com.ragvault.core.service;

import com.ragvault.core.domain.DataSourceConfig;
import com.ragvault.core.domain.SqlTableConfig;
import com.ragvault.core.repository.RoutingEmbeddingRepository;
import com.ragvault.core.repository.RoutingEmbeddingRepositoryCustom.EmbRow;
import com.ragvault.core.repository.SqlTableConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 데이터소스/테이블 설명 임베딩 색인 + 라우팅 후보 검색 (Phase C, 하이브리드의 임베딩 절반).
 *
 * 데이터소스가 많을 때 LLM 라우팅 프롬프트를 작게 유지하기 위해, 질문과 의미적으로
 * 가까운 데이터소스 후보만 추린다. 데이터소스가 적으면 사용되지 않는다(DataSourceRouterService 판단).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingEmbeddingService {

    private final OllamaEmbeddingModel embeddingModel;
    private final RoutingEmbeddingRepository repository;
    private final SqlTableConfigRepository sqlTableConfigRepository;
    private final DataSourceConfigService dataSourceConfigService;

    /** 특정 데이터소스의 라우팅 임베딩 재색인 (데이터소스 설명 + 활성 테이블 설명). */
    public void reindexDatasource(Integer datasourceId) {
        List<EmbRow> rows = new ArrayList<>();
        try {
            DataSourceConfig ds = dataSourceConfigService.findById(datasourceId);
            if (ds != null) {
                String dsContent = ds.getName()
                        + (ds.getDescription() != null && !ds.getDescription().isBlank()
                            ? ": " + ds.getDescription() : "");
                rows.add(new EmbRow(null, dsContent, embeddingModel.embed(dsContent)));
            }

            for (SqlTableConfig t : sqlTableConfigRepository.findByDatasourceIdAndIsActiveTrue(datasourceId)) {
                String desc = t.getDescription();
                String content = t.getSourceTable()
                        + (desc != null && !desc.isBlank() ? ": " + desc : "");
                rows.add(new EmbRow(t.getSourceTable(), content, embeddingModel.embed(content)));
            }

            repository.replaceForDatasource(datasourceId, rows);
            log.debug("Routing embedding reindexed: dsId={}, rows={}", datasourceId, rows.size());
        } catch (Exception e) {
            log.warn("Routing embedding reindex failed (non-fatal): dsId={}, error={}",
                    datasourceId, e.getMessage());
        }
    }

    /**
     * 질문에 유사한 데이터소스 후보 ID 목록. 임베딩/검색 실패 시 빈 리스트(호출부에서 전체 fallback).
     */
    public List<Integer> findCandidateDatasourceIds(String question, int topK) {
        try {
            String json = toJsonArray(embeddingModel.embed(question));
            return repository.searchCandidateDatasourceIds(json, topK);
        } catch (Exception e) {
            log.warn("Routing candidate search failed (non-fatal): {}", e.getMessage());
            return List.of();
        }
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
