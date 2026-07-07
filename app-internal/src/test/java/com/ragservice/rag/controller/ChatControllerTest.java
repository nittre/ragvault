package com.ragservice.rag.controller;

import com.ragservice.rag.dto.ChatCompletionRequest;
import com.ragservice.rag.dto.ChatMessage;
import com.ragservice.rag.dto.EffectiveParams;
import com.ragservice.rag.dto.MessageDto;
import com.ragservice.rag.service.AuditLogService;
import com.ragservice.rag.service.ParameterResolver;
import com.ragservice.rag.service.QueryRouterService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChatController 단위 테스트 — max_history_turns 파라미터 배선 검증.
 *
 * ADR-0005 7단계 우선순위 체인(ParameterResolver)이 계산한 max_history_turns 값이
 * 실제로 히스토리 길이 제한에 반영되는지 검증한다. 이전에는 이 값이 로그에만 찍히고
 * extractHistory()의 하드코딩된 10으로 항상 잘렸다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatControllerTest {

    @Mock QueryRouterService queryRouterService;
    @Mock ParameterResolver parameterResolver;
    @Mock AuditLogService auditLogService;
    @Mock HttpServletRequest httpServletRequest;

    @InjectMocks
    ChatController chatController;

    private static final QueryRouterService.RouterResult STUB_RESULT =
            new QueryRouterService.RouterResult("답변", List.of(), "RAG", null, false, null, List.of());

    @BeforeEach
    void setUp() {
        when(queryRouterService.route(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(STUB_RESULT);
    }

    private ChatCompletionRequest requestWithMessages(List<ChatMessage> messages) {
        return new ChatCompletionRequest("rag-auto", messages, null, null, null, null, null, null, null);
    }

    private List<ChatMessage> messagesWithPriorCount(int priorCount) {
        List<ChatMessage> messages = new java.util.ArrayList<>();
        for (int i = 0; i < priorCount; i++) {
            String role = (i % 2 == 0) ? "user" : "assistant";
            messages.add(new ChatMessage(role, "m" + i));
        }
        messages.add(new ChatMessage("user", "현재 질문"));
        return messages;
    }

    @SuppressWarnings("unchecked")
    private List<MessageDto> captureHistory(ChatCompletionRequest request) {
        chatController.chatCompletions(request, null, null, httpServletRequest);
        ArgumentCaptor<List<MessageDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(queryRouterService).route(anyString(), captor.capture(), any(), any(), any(), any());
        return captor.getValue();
    }

    @Test
    void resolvedMaxHistoryTurns_limitsHistorySize() {
        when(parameterResolver.resolve(any(), any(), any()))
                .thenReturn(EffectiveParams.of(Map.of("max_history_turns", 3), Map.of()));

        List<ChatMessage> messages = messagesWithPriorCount(7); // 7개 이전 메시지 + 현재 질문
        List<MessageDto> history = captureHistory(requestWithMessages(messages));

        assertThat(history).hasSize(3);
        assertThat(history.stream().map(MessageDto::content)).containsExactly("m4", "m5", "m6");
    }

    @Test
    void missingMaxHistoryTurns_fallsBackToDefaultTen() {
        when(parameterResolver.resolve(any(), any(), any()))
                .thenReturn(EffectiveParams.of(Map.of(), Map.of()));

        List<ChatMessage> messages = messagesWithPriorCount(12); // 10개 초과 → 폴백 기본값(10)으로 잘려야 함
        List<MessageDto> history = captureHistory(requestWithMessages(messages));

        assertThat(history).hasSize(10);
        assertThat(history.get(0).content()).isEqualTo("m2");
    }

    @Test
    void nonNumericMaxHistoryTurns_fallsBackToDefaultTen() {
        when(parameterResolver.resolve(any(), any(), any()))
                .thenReturn(EffectiveParams.of(Map.of("max_history_turns", "invalid"), Map.of()));

        List<ChatMessage> messages = messagesWithPriorCount(12);
        List<MessageDto> history = captureHistory(requestWithMessages(messages));

        assertThat(history).hasSize(10);
    }
}
