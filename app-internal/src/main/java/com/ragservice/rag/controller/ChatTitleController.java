package com.ragservice.rag.controller;

import com.ragvault.core.prompt.PromptLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * POST /api/v1/chat/title
 * Body  : { "userMessage": "..." }
 * Return: { "title": "..." }
 *
 * RAG 파이프라인을 우회하여 ChatClient 로 Ollama 에 직접 요청한다.
 * 첫 번째 사용자 메시지를 기반으로 20자 이내 한국어 제목을 반환한다.
 * 인증: anyRequest().authenticated() -- JWT 로그인 사용자 전용.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatTitleController {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT =
            PromptLoader.load("prompts/chat-title/system.txt");

    @PostMapping("/title")
    public ResponseEntity<Map<String, String>> generateTitle(
            @RequestBody Map<String, String> body) {

        String userMessage = body.getOrDefault("userMessage", "").trim();
        if (userMessage.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userMessage is required"));
        }

        String truncated = userMessage.length() > 200 ? userMessage.substring(0, 200) : userMessage;

        try {
            String title = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(truncated)
                    .call()
                    .content();

            // Remove straight/curly quotes, keep only first line, trim whitespace
            String cleaned = title == null ? "" : title
                    .replaceAll("[\"'`]", "")
                    .replaceAll("\\r?\\n.*", "")
                    .trim();

            if (cleaned.length() > 30) {
                cleaned = cleaned.substring(0, 30);
            }

            log.debug("Title generated: [{}]", cleaned);
            return ResponseEntity.ok(Map.of("title", cleaned));

        } catch (Exception e) {
            log.warn("Title generation failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
