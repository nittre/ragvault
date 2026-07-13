package com.ragservice.rag.service;

import com.ragservice.rag.dto.EffectiveParams;
import com.ragservice.rag.dto.MessageDto;
import com.ragvault.core.domain.DocumentChunk.ChunkResult;
import com.ragvault.core.service.QueryIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * QueryRouterService 단위 테스트 — RAG 검색 결과 없을 때 WEB_SEARCH 폴백 검증.
 *
 * "/rag" 강제 명령(routeForceRag)에는 이미 있던 RAG→WEB_SEARCH 폴백을, 자동 분류로
 * RAG가 선택되는 일반 경로(route())에도 적용했다. RAG로 시작한 대화의 후속 질문
 * (예: 답변 중 등장한 용어를 되묻는 질문)이 내부 문서에 없을 때 실패 응답 대신
 * 웹 검색으로 이어지는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueryRouterServiceTest {

    @Mock IntentClassifierService intentClassifier;
    @Mock RagService ragService;
    @Mock TextToSqlService textToSqlService;
    @Mock HybridQueryService hybridQueryService;
    @Mock UrlFetchService urlFetchService;
    @Mock FileContextService fileContextService;
    @Mock ImagePathService imagePathService;
    @Mock WebSearchService webSearchService;
    @Mock ResponseRawStorageService rawStorage;
    @Mock RagMetricsService metricsService;

    @InjectMocks
    QueryRouterService queryRouterService;

    private static final EffectiveParams EMPTY_PARAMS = EffectiveParams.of(Map.of(), Map.of());

    @BeforeEach
    void setUp() {
        when(intentClassifier.classify(anyString(), any(), any())).thenReturn(QueryIntent.RAG);
        when(rawStorage.store(anyString(), anyString(), any(), anyString())).thenReturn("resp_raw:test");
    }

    @Test
    void ragWithSources_doesNotFallBackToWebSearch() {
        List<ChunkResult> sources = List.of(new ChunkResult("청크 내용", "knowledge_doc", "doc-1", 0.9));
        RagService.RagResult ragResult = RagService.RagResult.success("RAG 답변", sources);
        when(ragService.chat(anyString(), any(), any())).thenReturn(ragResult);

        QueryRouterService.RouterResult result =
                queryRouterService.route("LLM2가 뭔데?", List.of(), "user@test.com", null, null, EMPTY_PARAMS);

        assertThat(result.intent()).isEqualTo("RAG");
        assertThat(result.content()).isEqualTo("RAG 답변");
        verifyNoInteractions(webSearchService);
    }

    @Test
    void ragNoSources_fallsBackToWebSearch_whenWebSearchEnabled() {
        ReflectionTestUtils.setField(queryRouterService, "webSearchEnabled", true);
        RagService.RagResult ragResult =
                RagService.RagResult.noContext("관련된 정보를 자료에서 찾을 수 없습니다.");
        when(ragService.chat(anyString(), any(), any())).thenReturn(ragResult);
        when(webSearchService.search(anyString(), any()))
                .thenReturn(new WebSearchService.WebSearchResult("웹 검색 답변", List.of("http://example.com"),
                        "resp_raw:web", false));

        QueryRouterService.RouterResult result =
                queryRouterService.route("LLM2가 뭔데?", List.of(new MessageDto("user", "이전 질문")),
                        "user@test.com", null, null, EMPTY_PARAMS);

        assertThat(result.intent()).isEqualTo("WEB_SEARCH");
        assertThat(result.content()).isEqualTo("웹 검색 답변");
        verify(webSearchService).search("LLM2가 뭔데?", "user@test.com");
    }

    @Test
    void ragNoSources_doesNotFallBack_whenWebSearchDisabled() {
        ReflectionTestUtils.setField(queryRouterService, "webSearchEnabled", false);
        RagService.RagResult ragResult =
                RagService.RagResult.noContext("관련된 정보를 자료에서 찾을 수 없습니다.");
        when(ragService.chat(anyString(), any(), any())).thenReturn(ragResult);

        QueryRouterService.RouterResult result =
                queryRouterService.route("LLM2가 뭔데?", List.of(), "user@test.com", null, null, EMPTY_PARAMS);

        assertThat(result.intent()).isEqualTo("RAG");
        assertThat(result.content()).isEqualTo("관련된 정보를 자료에서 찾을 수 없습니다.");
        verifyNoInteractions(webSearchService);
    }
}
