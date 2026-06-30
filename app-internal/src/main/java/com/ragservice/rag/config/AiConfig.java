package com.ragservice.rag.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring AI ChatClient 설정.
 *
 * chatClient    — 기본 LLM (RAG/SQL/HYBRID/FILE/URL, 모델은 application.yml 설정)
 * vlmChatClient — 이미지 분석 전용 (IMAGE/IMAGE_RAG 경로, temperature 낮게)
 *                 dev에서는 chat과 동일 모델(qwen2.5-vl)로 통합 → 재로드 최소화
 *
 * ADR-0004: Spring AI 전면 사용
 * LL-0002: @ConditionalOnMissingBean — 테스트 mock 감지용
 */
@Configuration
public class AiConfig {

    @Bean
    @Primary
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /**
     * 이미지 분석 전용 ChatClient — temperature 낮게, numCtx 넉넉히.
     * @ConditionalOnMissingBean(name="vlmChatClient"): 테스트에서 mock으로 대체 가능.
     */
    @Bean("vlmChatClient")
    @ConditionalOnMissingBean(name = "vlmChatClient")
    public ChatClient vlmChatClient(
            OllamaApi ollamaApi,
            @Value("${rag.vlm.model:qwen2.5vl:7b}") String vlmModel) {
        OllamaOptions options = OllamaOptions.builder()
                .model(vlmModel)
                .temperature(0.3)
                // 기본 chatClient(application.yml num-ctx)와 동일 값 유지.
                // 같은 모델을 두 ChatClient가 다른 num_ctx로 호출하면 Ollama가 컨텍스트를
                // 재할당하며 모델을 reload할 수 있어, 값을 맞춰 reload를 방지한다.
                .numCtx(8192)
                .build();
        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(options)
                .build();
        return ChatClient.builder(model).build();
    }
}
