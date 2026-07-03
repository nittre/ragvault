package com.ragservice.rag.service;

import com.ragservice.rag.dto.MessageDto;
import com.ragservice.rag.security.InputValidator;
import com.ragvault.core.repository.DocumentChunkRepository;
import com.ragvault.core.security.PiiMasker;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagService 단위 테스트 — 멀티턴 후속 질문 검색 재작성 로직 검증.
 *
 * 후속 질문("좀 더 자세히 설명해줘")이 그 자체로는 문서와 의미적으로 유사하지 않아
 * 검색이 실패하는 문제를 막기 위해, 대화 이력이 있을 때 검색 쿼리를 독립형 질문으로
 * 재작성한 뒤 임베딩하도록 수정했다. 이 테스트는 그 재작성 분기를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagServiceTest {

    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.CallResponseSpec callResponseSpec;
    @Mock OllamaEmbeddingModel embeddingModel;
    @Mock DocumentChunkRepository chunkRepository;
    @Mock PiiMasker piiMasker;
    @Mock InputValidator inputValidator;

    @InjectMocks
    RagService ragService;

    @BeforeEach
    void setUp() {
        when(inputValidator.validate(anyString()))
                .thenReturn(new InputValidator.ValidationResult(true, null));
        when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);
        // 청크 검색 결과를 비워 재작성 이후 흐름(LLM 최종 호출)을 테스트 범위 밖으로 둔다.
        when(chunkRepository.findSimilarChunks(anyString(), anyDouble(), anyInt())).thenReturn(List.of());

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        ReflectionTestUtils.setField(ragService, "noResultsResponse", "NO_RESULTS");
    }

    @Test
    void noHistory_embedsOriginalMessage_withoutCallingLlm() {
        ragService.chat("RAG 시스템 설계할 때 고려해야할거 알려줘", List.of());

        verify(embeddingModel).embed("RAG 시스템 설계할 때 고려해야할거 알려줘");
        verifyNoInteractions(chatClient);
    }

    @Test
    void followUpWithHistory_rewritesQuery_andEmbedsRewrittenText() {
        when(callResponseSpec.content()).thenReturn("RAG 시스템 설계 시 고려사항에 대해 더 자세히 설명해줘");

        List<MessageDto> history = List.of(
                new MessageDto("user", "RAG 시스템 설계할 때 고려해야할거 알려줘"),
                new MessageDto("assistant", "청킹 전략, 임베딩 모델 선택, 검색 정확도 등을 고려해야 합니다."));

        ragService.chat("좀 더 자세하게 설명해줘", history);

        verify(chatClient, times(1)).prompt();
        verify(embeddingModel).embed("RAG 시스템 설계 시 고려사항에 대해 더 자세히 설명해줘");
        verify(embeddingModel, never()).embed("좀 더 자세하게 설명해줘");
    }

    @Test
    void rewriteLlmFailure_fallsBackToOriginalMessage() {
        when(chatClient.prompt()).thenThrow(new RuntimeException("LLM unavailable"));

        List<MessageDto> history = List.of(
                new MessageDto("user", "RAG 시스템 설계할 때 고려해야할거 알려줘"),
                new MessageDto("assistant", "청킹 전략, 임베딩 모델 선택 등을 고려해야 합니다."));

        RagService.RagResult result = ragService.chat("좀 더 자세하게 설명해줘", history);

        verify(embeddingModel).embed("좀 더 자세하게 설명해줘");
        assertThat(result.content()).isEqualTo("NO_RESULTS");
        assertThat(result.blocked()).isFalse();
    }
}
