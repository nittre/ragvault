package com.ragservice.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Discord Webhook 알림 컴포넌트.
 *
 * discord-webhook-url이 비어있으면 로그로만 출력.
 */
@Component
@Slf4j
public class DiscordNotifier {

    @Value("${rag.sync.discord-webhook-url:}")
    private String webhookUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public void info(String message) {
        send("INFO: " + message);
    }

    public void warning(String message) {
        send("WARNING: " + message);
    }

    public void critical(String message) {
        send("CRITICAL: " + message);
    }

    private void send(String message) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.info("[Discord] {}", message);
            return;
        }
        try {
            Map<String, String> body = Map.of("content", message);
            restTemplate.postForEntity(webhookUrl, body, String.class);
        } catch (Exception e) {
            log.warn("Discord webhook failed: {}", e.getMessage());
        }
    }
}
