package com.ragservice.rag.service;

import com.ragservice.rag.repository.BusinessKnowledgeRepository;
import com.ragservice.rag.repository.BusinessKnowledgeRepositoryCustom.KnowledgeRow;
import com.ragservice.rag.service.BusinessRuleService.KnowledgeEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.ollama.OllamaEmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BusinessRuleService 단위 테스트 — 레코드 단위 백과사전 구조.
 * - reindex: title+content 임베딩, pinned/role 분리 저장, pinned 메모리 캐시 갱신
 * - collectRelevant: pinned(캐시) 항상 주입 + 동적 top-k 병합, role별 포맷
 * - 캐시 lazy-load: 캐시 미스 시 findPinned 사용
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BusinessRuleServiceTest {

    @Mock OllamaEmbeddingModel embeddingModel;
    @Mock BusinessKnowledgeRepository repository;

    @InjectMocks
    BusinessRuleService service;

    @Test
    void reindex_storesPinnedRoleAndEmbedsTitlePlusContent() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

        service.reindex(1, List.of(
                new KnowledgeEntry("순매출", "rule", "매출 = amount - cancel", true),
                new KnowledgeEntry("진행중 부트캠프", "measure", "SELECT * FROM bootcamp WHERE status='ongoing'", false),
                new KnowledgeEntry("빈 항목", "rule", "  ", false)   // content 공백 → skip
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeRow>> cap = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceForDatasource(eq(1), cap.capture());

        List<KnowledgeRow> rows = cap.getValue();
        assertThat(rows).hasSize(2);   // 공백 content 제외
        assertThat(rows).anyMatch(r -> r.pinned() && "rule".equals(r.knowledgeRole())
                && r.content().equals("매출 = amount - cancel") && "순매출".equals(r.title()));
        assertThat(rows).anyMatch(r -> !r.pinned() && "measure".equals(r.knowledgeRole())
                && r.content().contains("bootcamp"));

        // 임베딩 소스 = title + "\n" + content
        verify(embeddingModel).embed("순매출\n매출 = amount - cancel");
    }

    @Test
    void reindex_emptyEntries_replacesWithEmpty() {
        service.reindex(2, List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeRow>> cap = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceForDatasource(eq(2), cap.capture());
        assertThat(cap.getValue()).isEmpty();
    }

    @Test
    void collectRelevant_usesPinnedCacheFromReindex_andMergesDynamic() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.3f});

        // reindex → pinned 캐시에 적재 (DB findPinned 호출 없이 캐시 사용)
        service.reindex(1, List.of(
                new KnowledgeEntry("순매출", "rule", "매출 = amount - cancel", true)));

        when(repository.searchDynamic(anyString(), eq(1), anyDouble(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{"진행중 부트캠프", "SELECT ...", "measure", 0.9}));

        String result = service.collectRelevant("질문", 1);

        assertThat(result).contains("# 규칙: 순매출").contains("매출 = amount - cancel");
        assertThat(result).contains("# 측정 정의: 진행중 부트캠프").contains("SELECT ...");
        verify(repository, never()).findPinned(anyInt());   // 캐시 히트
    }

    @Test
    void collectRelevant_cacheMiss_lazyLoadsFromDb() {
        when(repository.findPinned(5))
                .thenReturn(List.<Object[]>of(new Object[]{"고정 규칙", "정의 본문", "rule"}));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.3f});
        when(repository.searchDynamic(anyString(), eq(5), anyDouble(), anyInt()))
                .thenReturn(List.of());

        String result = service.collectRelevant("질문", 5);   // reindex 없이 호출 → 캐시 미스

        verify(repository).findPinned(5);
        assertThat(result).contains("# 규칙: 고정 규칙").contains("정의 본문");
    }

    @Test
    void collectRelevant_nullDatasource_returnsEmpty() {
        assertThat(service.collectRelevant("질문", null)).isEmpty();
    }
}
