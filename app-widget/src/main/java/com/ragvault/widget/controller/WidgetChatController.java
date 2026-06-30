package com.ragvault.widget.controller;

import com.ragvault.core.domain.DocumentChunk.ChunkResult;
import com.ragvault.widget.dto.ChatMessage;
import com.ragvault.core.dto.CitationSource;
import com.ragvault.widget.dto.WidgetChatRequest;
import com.ragvault.widget.dto.WidgetChatResponse;
import com.ragvault.widget.service.QueryRouterService;
import com.ragvault.widget.service.QueryRouterService.RouterResult;
import com.ragvault.widget.service.SearchConfigService;
import com.ragvault.widget.service.WidgetRagService;
import com.ragvault.widget.service.WidgetRagService.HistoryMessage;
import com.ragvault.widget.service.WidgetRagService.RagResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * POST /v1/widget/chat — 위젯 챗봇 엔드포인트.
 *
 * 요청:  {messages:[{role, content}]}, 헤더 X-Site-Key
 * 응답:  OpenAI 호환 — choices[0].message.content + citations
 *
 * 처리 순서:
 * 1. SiteKeyFilter 에서 X-Site-Key 검증 (401 if invalid)
 * 2. 마지막 user 메시지 추출
 * 3. WidgetRagService.chat() — RAG 검색 → LLM 생성 → PII 마스킹
 * 4. OpenAI 호환 응답 포맷팅
 *
 * SQL/파일/URL 경로 없음 — FAQ RAG 전용.
 * 사내 MySQL DataSource 없음.
 */
@Slf4j
@RestController
@RequestMapping("/v1/widget")
@RequiredArgsConstructor
public class WidgetChatController {

    private static final int MAX_HISTORY_TURNS = 6;

    private final WidgetRagService ragService;
    private final SearchConfigService searchConfigService;
    private final QueryRouterService queryRouterService;

    @PostMapping("/chat")
    public ResponseEntity<WidgetChatResponse> chat(
            @RequestBody WidgetChatRequest request,
            @RequestHeader("X-Site-Key") String siteKey,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionIdHeader) {

        if (request.messages() == null || request.messages().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // 마지막 user 메시지 추출
        String userMessage = extractLastUserMessage(request.messages());
        if (userMessage == null || userMessage.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // 세션 ID: 헤더 우선, 없으면 UUID 생성
        String sessionId = (sessionIdHeader != null && !sessionIdHeader.isBlank())
                ? sessionIdHeader
                : UUID.randomUUID().toString();

        // 대화 이력 (마지막 user 메시지 제외, 최대 MAX_HISTORY_TURNS 턴)
        List<HistoryMessage> history = extractHistory(request.messages());

        log.debug("Widget chat request: siteKey={}, sessionId={}, messageLen={}, historyTurns={}",
                siteKey, sessionId, userMessage.length(), history.size());

        // 처리: sql_enabled=true 면 RAG/SQL 라우팅, 아니면 기존 FAQ RAG 전용
        String content;
        List<ChunkResult> sources;
        if (searchConfigService.getSqlEnabled()) {
            // text-to-sql 허용 — 공개 위젯 기본 비활성(본인 인증 흐름 부재 시 내부/데모 용도)
            RouterResult routed = queryRouterService.route(userMessage, history, "widget:" + siteKey);
            content = routed.content();
            sources = routed.sources();
        } else {
            RagResult result = ragService.chat(userMessage, history, sessionId, siteKey);
            content = result.content();
            sources = result.sources();
        }

        // citations 변환
        List<CitationSource> citations = sources.stream()
                .map(c -> new CitationSource(c.sourceId(), c.sourceTable(), c.score()))
                .toList();

        // OpenAI 호환 응답 포맷팅
        ChatMessage assistantMessage = new ChatMessage("assistant", content);
        WidgetChatResponse.Choice choice = new WidgetChatResponse.Choice(0, assistantMessage, "stop");

        WidgetChatResponse response = new WidgetChatResponse(
                "widget-" + UUID.randomUUID(),
                "chat.completion",
                Instant.now().getEpochSecond(),
                "qwen2.5",
                List.of(choice),
                citations
        );

        return ResponseEntity.ok(response);
    }

    private String extractLastUserMessage(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if ("user".equals(m.role())) {
                return m.textContent();
            }
        }
        return null;
    }

    private List<HistoryMessage> extractHistory(List<ChatMessage> messages) {
        // 마지막 user 메시지 인덱스 찾기
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                lastUserIdx = i;
                break;
            }
        }

        if (lastUserIdx <= 0) return List.of();

        // 이전 메시지들 (system 제외), 최근 MAX_HISTORY_TURNS * 2 개
        List<ChatMessage> prior = messages.subList(0, lastUserIdx);
        int start = Math.max(0, prior.size() - MAX_HISTORY_TURNS * 2);
        return prior.subList(start, prior.size()).stream()
                .filter(m -> !"system".equals(m.role()))
                .map(m -> new HistoryMessage(m.role(), m.textContent()))
                .toList();
    }
}
