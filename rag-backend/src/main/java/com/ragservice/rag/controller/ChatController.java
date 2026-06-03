package com.ragservice.rag.controller;

import com.ragservice.rag.dto.*;
import com.ragservice.rag.service.ParameterResolver;
import com.ragservice.rag.service.QueryRouterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
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
 * ADR-0006: TrustedHeaderFilter 가 X-User-* 외부 주입 차단
 * ADR-0010: responseId 응답에 포함
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

    /**
     * OpenAI 호환 /v1/models — Open WebUI가 모델 목록 조회 시 호출.
     * RAG 백엔드를 통해 사용 가능한 모델을 노출한다.
     */
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
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-Conversation-Id", required = false) String conversationId) {

        // 메시지에서 텍스트 + 이미지 추출
        String userMessage = extractLastUserMessage(request.messages());
        List<String> images = mergeImages(request.messages(), request.images());
        List<MessageDto> history = extractHistory(request.messages());

        log.debug("Chat completions: model={}, messages={}, images={}, fileIds={}",
                request.model(),
                request.messages() != null ? request.messages().size() : 0,
                images.size(),
                request.fileIds() != null ? request.fileIds().size() : 0);

        // M5-2: ADR-0005 7단계 파라미터 우선순위 체인 적용
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

    /** 마지막 user 메시지의 텍스트를 추출한다 (멀티모달 포함). */
    private String extractLastUserMessage(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return "";
        return messages.stream()
                .filter(m -> "user".equals(m.role()))
                .reduce((first, second) -> second)
                .map(ChatMessage::textContent)
                .orElse("");
    }

    /**
     * 마지막 user 메시지의 content 배열에서 이미지를 추출해 explicit 목록과 합친다.
     * 히스토리(이전 메시지)의 이미지는 포함하지 않는다 — 포함하면 텍스트 전용 후속 메시지가
     * IMAGE 경로로 잘못 라우팅된다.
     */
    private List<String> mergeImages(List<ChatMessage> messages, List<String> explicit) {
        List<String> result = new ArrayList<>();
        if (explicit != null) result.addAll(explicit);
        if (messages != null) {
            // 마지막 user 메시지에서만 이미지 추출
            messages.stream()
                    .filter(m -> "user".equals(m.role()))
                    .reduce((first, second) -> second)  // 마지막 user 메시지
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
