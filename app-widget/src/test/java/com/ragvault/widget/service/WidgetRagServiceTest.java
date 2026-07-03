package com.ragvault.widget.service;

import com.ragvault.core.repository.DocumentChunkRepository;
import com.ragvault.core.security.PiiMasker;
import com.ragvault.widget.security.InputValidator;
import com.ragvault.widget.service.WidgetRagService.HistoryMessage;
import com.ragvault.widget.service.WidgetRagService.RagResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaEmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WidgetRagService 단위 테스트 — 멀티턴 후속 질문 검색 재작성 로직 검증.
 *
 * 챗 서비스(RagServiceTest)와 동일한 구조적 결함 — 후속 질문이 그 자체로는 문서와
 * 의미적으로 유사하지 않아 검색이 실패하는 문제 — 을 위젯 서비스에서도 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WidgetRagServiceTest {

    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.CallResponseSpec callResponseSpec;
    @Mock OllamaEmbeddingModel embeddingModel;
    @Mock DocumentChunkRepository chunkRepository;
    @Mock PiiMasker piiMasker;
    @Mock InputValidator inputValidator;
    @Mock ConversationLogService conversationLogService;
    @Mock SearchConfigService searchConfigService;

    @InjectMocks
    WidgetRagService widgetRagService;

    @BeforeEach
    void setUp() {
        when(searchConfigService.getTopK()).thenReturn(5);
        when(searchConfigService.getThreshold()).thenReturn(0.55);
        when(searchConfigService.getNoResultsResponse()).thenReturn("NO_RESULTS");
        when(searchConfigService.getInjectionBlockedResponse()).thenReturn("BLOCKED");

        when(inputValidator.validate(anyString()))
                .thenReturn(new InputValidator.ValidationResult(true, null));
        when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);
        // 청크 검색 결과를 비워 재작성 이후 흐름(LLM 최종 호출)을 테스트 범위 밖으로 둔다.
        when(chunkRepository.findSimilarChunks(anyString(), anyDouble(), anyInt())).thenReturn(List.of());

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    @Test
    void noHistory_embedsOriginalMessage_withoutCallingLlm() {
        widgetRagService.chat("RAG 시스템 설계할 때 고려해야할거 알려줘", List.of());

        verify(embeddingModel).embed("RAG 시스템 설계할 때 고려해야할거 알려줘");
        verifyNoInteractions(chatClient);
    }

    @Test
    void followUpWithHistory_rewritesQuery_andEmbedsRewrittenText() {
        when(callResponseSpec.content()).thenReturn("RAG 시스템 설계 시 고려사항에 대해 더 자세히 설명해줘");

        List<HistoryMessage> history = List.of(
                new HistoryMessage("user", "RAG 시스템 설계할 때 고려해야할거 알려줘"),
                new HistoryMessage("assistant", "청킹 전략, 임베딩 모델 선택, 검색 정확도 등을 고려해야 합니다."));

        widgetRagService.chat("좀 더 자세하게 설명해줘", history);

        verify(chatClient, times(1)).prompt();
        verify(embeddingModel).embed("RAG 시스템 설계 시 고려사항에 대해 더 자세히 설명해줘");
        verify(embeddingModel, never()).embed("좀 더 자세하게 설명해줘");
    }

    @Test
    void rewriteLlmFailure_fallsBackToOriginalMessage() {
        when(chatClient.prompt()).thenThrow(new RuntimeException("LLM unavailable"));

        List<HistoryMessage> history = List.of(
                new HistoryMessage("user", "RAG 시스템 설계할 때 고려해야할거 알려줘"),
                new HistoryMessage("assistant", "청킹 전략, 임베딩 모델 선택 등을 고려해야 합니다."));

        RagResult result = widgetRagService.chat("좀 더 자세하게 설명해줘", history);

        verify(embeddingModel).embed("좀 더 자세하게 설명해줘");
        assertThat(result.content()).isEqualTo("NO_RESULTS");
        assertThat(result.blocked()).isFalse();
    }
}
