package com.ragvault.widget.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI ChatClient 설정.
 *
 * ADR-0004(rag-practice): Spring AI 전면 사용.
 * LL-0002: @ConditionalOnMissingBean — 테스트 mock 감지용.
 */
@Configuration
public class AiConfig {

    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
