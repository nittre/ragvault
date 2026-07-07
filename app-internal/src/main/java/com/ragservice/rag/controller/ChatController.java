package com.ragservice.rag.controller;

import com.ragservice.rag.dto.*;
import com.ragservice.rag.service.AuditLogService;
import com.ragservice.rag.service.ParameterResolver;
import com.ragservice.rag.service.QueryRouterService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import com.ragvault.core.dto.CitationSource;
import java.util.ArrayList;
import com.ragvault.core.dto.CitationSource;
import java.util.List;
import com.ragvault.core.dto.CitationSource;
import java.util.Map;
import com.ragvault.core.dto.CitationSource;
import java.util.UUID;
import com.ragvault.core.dto.CitationSource;

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
    private final AuditLogService auditLogService;

    @Value("${rag.chat.model:qwen2.5vl:7b}")
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
            @RequestHeader(value = "X-Conversation-Id", required = false) String conversationId,
            HttpServletRequest httpRequest) {

        // ADR-0011: SecurityContext 에서 이메일 추출
        String userEmail = authentication != null ? authentication.getName() : null;

        String userMessage = extractLastUserMessage(request.messages());
        List<String> images = mergeImages(request.messages(), request.images());

        // ADR-0005 7단계 파라미터 우선순위 체인 — history 추출보다 먼저 계산해야
        // max_history_turns 가 실제로 적용된다.
        EffectiveParams effectiveParams = parameterResolver.resolve(
                userEmail,
                conversationId,
                request.ragParams());
        log.debug("Effective params resolved: user={}, conv={}, sources={}",
                userEmail, conversationId, effectiveParams.sources());

        int maxHistoryMessages = resolveMaxHistoryMessages(effectiveParams);
        List<MessageDto> history = extractHistory(request.messages(), maxHistoryMessages);

        log.debug("Chat completions: model={}, messages={}, images={}, fileIds={}",
                request.model(),
                request.messages() != null ? request.messages().size() : 0,
                images.size(),
                request.fileIds() != null ? request.fileIds().size() : 0);

        QueryRouterService.RouterResult result = queryRouterService.route(
                userMessage, history, userEmail,
                images,
                request.fileIds(),
                request.routingHint());

        auditLogService.log(userEmail, resolveAction(result.intent()), result.intent(),
                userMessage, httpRequest.getRemoteAddr(), result.responseId());

        return ResponseEntity.ok(buildResponse(result, request.model()));
    }

    /** audit_log.action 은 'CHAT'/'SQL_QUERY'/'FILE_UPLOAD' 로 집계된다 (AdminUsageStatsController). */
    private String resolveAction(String intent) {
        return switch (intent) {
            case "SQL" -> "SQL_QUERY";
            case "FILE" -> "FILE_UPLOAD";
            default -> "CHAT";
        };
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
        if (explicit != null) {
            explicit.stream()
                    .map(s -> { int i = s.indexOf(','); return i >= 0 ? s.substring(i + 1) : s; })
                    .filter(s -> !s.isBlank())
                    .forEach(result::add);
        }
        if (messages != null) {
            messages.stream()
                    .filter(m -> "user".equals(m.role()))
                    .reduce((first, second) -> second)
                    .ifPresent(last -> result.addAll(last.extractImages()));
        }
        return result;
    }

    private List<MessageDto> extractHistory(List<ChatMessage> messages, int maxMessages) {
        if (messages == null || messages.size() <= 1) return List.of();
        List<ChatMessage> history = messages.subList(0, messages.size() - 1);
        int start = Math.max(0, history.size() - maxMessages);
        return history.subList(start, history.size()).stream()
                .map(m -> new MessageDto(m.role(), m.textContent()))
                .toList();
    }

    /**
     * ADR-0005 파라미터 체인이 계산한 max_history_turns 를 히스토리 메시지 개수 제한으로 사용한다.
     * 값이 없거나 숫자가 아니면 기존 하드코딩 기본값(10)으로 폴백한다.
     */
    private int resolveMaxHistoryMessages(EffectiveParams effectiveParams) {
        Object value = effectiveParams.values().get("max_history_turns");
        return (value instanceof Number n) ? n.intValue() : 10;
    }

    private ChatCompletionResponse buildResponse(QueryRouterService.RouterResult result, String model) {
        String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        long created = Instant.now().getEpochSecond();
        String modelName = model != null ? model : defaultChatModel;

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
                result.intent(), result.responseId(), result.generatedSql(), result.sourceUrls());
    }
}
