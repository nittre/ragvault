package com.ragservice.rag.controller;

import com.ragservice.rag.dto.*;
import com.ragservice.rag.service.ParameterResolver;
import com.ragservice.rag.service.QueryRouterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI 호환 /v1/chat/completions.
 * M4: images, fileIds 를 QueryRouterService 에 전달 → 6경로 분기.
 *
 * ADR-0011: X-User-Email 헤더 → SecurityContext Authentication 으로 사용자 식별.
 * ADR-0010: responseId 응답에 포함.
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ChatController {

    private final QueryRouterService queryRouterService;
    private final ParameterResolver parameterResolver;

    @Value("${rag.chat.model:qwen2.5:7b-instruct-q4_K_M}")
    private String defaultChatModel;

    @Value("${rag.vlm.model:qwen2.5vl:7b}")
    private String defaultVlmModel;

    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> listModels() {
        List<Map<String, Object>> models = List.of(
                modelEntry("rag-auto",       "RAG 자동 라우팅 (텍스트·이미지·파일·SQL 자동 감지)"),
                modelEntry(defaultChatModel, "RAG 텍스트 채팅 (qwen2.5 7B)"),
                modelEntry(defaultVlmModel,  "이미지 분석 VLM (qwen2.5-vl 7B)")
        );
        return ResponseEntity.ok(Map.of("object", "list", "data", models));
    }

    private Map<String, Object> modelEntry(String id, String description) {
        return Map.of("id", id, "object", "model", "description", description,
                "created", Instant.now().getEpochSecond(), "owned_by", "rag-backend");
    }

    @PostMapping("/chat/completions")
    public ResponseEntity<ChatCompletionResponse> chatCompletions(
            @RequestBody ChatCompletionRequest request,
            Authentication authentication,
            @RequestHeader(value = "X-Conversation-Id", required = false) String conversationId) {

        // ADR-0011: SecurityContext 에서 이메일 추출
        String userEmail = authentication != null ? authentication.getName() : null;

        String userMessage = extractLastUserMessage(request.messages());
        List<String> images = mergeImages(request.messages(), request.images());
        List<MessageDto> history = extractHistory(request.messages());

        log.debug("Chat completions: model={}, messages={}, images={}, fileIds={}",
                request.model(),
                request.messages() != null ? request.messages().size() : 0,
                images.size(),
                request.fileIds() != null ? request.fileIds().size() : 0);

        // ADR-0005 7단계 파라미터 우선순위 체인 — 두 호출 모두 동일한 userEmail 전달
        EffectiveParams effectiveParams = parameterResolver.resolve(
                userEmail,
                conversationId,
                request.ragParams());
        log.debug("Effective params resolved: user={}, conv={}, sources={}",
                userEmail, conversationId, effectiveParams.sources());

        QueryRouterService.RouterResult result = queryRouterService.route(
                userMessage, history, userEmail,
                images.isEmpty() ? request.images() : images,
                request.fileIds());

        return ResponseEntity.ok(buildResponse(result, request.model()));
    }

    private String extractLastUserMessage(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return "";
        return messages.stream()
                .filter(m -> "user".equals(m.role()))
                .reduce((first, second) -> second)
                .map(ChatMessage::textContent)
                .orElse("");
    }

    private List<String> mergeImages(List<ChatMessage> messages, List<String> explicit) {
        List<String> result = new ArrayList<>();
        if (explicit != null) result.addAll(explicit);
        if (messages != null) {
            messages.stream()
                    .filter(m -> "user".equals(m.role()))
                    .reduce((first, second) -> second)
                    .ifPresent(last -> result.addAll(last.extractImages()));
        }
        return result;
    }

    private List<MessageDto> extractHistory(List<ChatMessage> messages) {
        if (messages == null || messages.size() <= 1) return List.of();
        List<ChatMessage> history = messages.subList(0, messages.size() - 1);
        int start = Math.max(0, history.size() - 10);
        return history.subList(start, history.size()).stream()
                .map(m -> new MessageDto(m.role(), m.textContent()))
                .toList();
    }

    private ChatCompletionResponse buildResponse(QueryRouterService.RouterResult result, String model) {
        String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        long created = Instant.now().getEpochSecond();
        String modelName = model != null ? model : "qwen2.5:7b-instruct-q4_K_M";

        ChatMessage msg = new ChatMessage("assistant", result.content());
        ChatCompletionResponse.Choice choice = new ChatCompletionResponse.Choice(0, msg, "stop");

        List<CitationSource> citations = result.sources().stream()
                .map(c -> new CitationSource(
                        c.sourceTable() + "#" + c.sourceId(),
                        c.sourceTable(),
                        c.score()))
                .toList();

        return new ChatCompletionResponse(id, "chat.completion", created, modelName,
                List.of(choice), citations,
                result.intent(), result.responseId(), result.generatedSql());
    }
}
