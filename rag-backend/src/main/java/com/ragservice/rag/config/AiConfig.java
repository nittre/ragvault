package com.ragservice.rag.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI ChatClient 설정.
 *
 * chatClient    — 기본 텍스트 LLM (qwen2.5:14b, RAG/SQL/HYBRID/FILE/URL)
 * vlmChatClient — 이미지 VLM (qwen2.5-vl:7b, IMAGE 경로)
 *
 * ADR-0004: Spring AI 전면 사용 + Q4_K_M 양자화
 * LL-0002: @ConditionalOnMissingBean — 테스트 mock 감지용
 *
 * OllamaChatModel 생성자 (Spring AI 1.0.0 GA):
 *   5인자 생성자 직접 호출 대신 OllamaChatModel.builder() 사용.
 */
@Configuration
public class AiConfig {

    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /**
     * VLM 전용 ChatClient — qwen2.5-vl 모델.
     * @ConditionalOnMissingBean(name="vlmChatClient"): 테스트에서 mock으로 대체 가능.
     */
    @Bean("vlmChatClient")
    @ConditionalOnMissingBean(name = "vlmChatClient")
    public ChatClient vlmChatClient(
            OllamaApi ollamaApi,
            @Value("${rag.vlm.model:qwen2.5-vl:7b-instruct-q4_K_M}") String vlmModel) {
        OllamaOptions options = OllamaOptions.builder()
                .model(vlmModel)
                .temperature(0.3)
                .build();
        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(options)
                .build();
        return ChatClient.builder(model).build();
    }
}
