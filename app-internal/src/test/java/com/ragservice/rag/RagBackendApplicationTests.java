package com.ragservice.rag;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.thymeleaf.TemplateEngine;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * M1 컨텍스트 로드 테스트.
 *
 * Ollama auto-config 제외 + 내부 @TestConfiguration으로 Mock ChatClient/OllamaEmbeddingModel 제공.
 *
 * @MockitoBean은 @ConditionalOnMissingBean 평가 이후에 적용되므로
 * AiConfig.chatClient()가 먼저 실행되어 ChatClient.Builder를 찾다가 실패한다.
 * @TestConfiguration은 ApplicationContext 생성 시 함께 등록되어
 * @ConditionalOnMissingBean이 올바르게 감지한다.
 *
 * LL-0002 참조: Spring Boot 3.5 + Spring AI 1.0.0 호환성 주의사항.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.autoconfigure.exclude=" +
            "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration," +
            "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration," +
            "org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration," +
            "org.springframework.boot.autoconfigure.mail.MailSenderValidatorAutoConfiguration",
        // @ConditionalOnMissingBean이 TestAiConfig보다 먼저 평가되어 AiConfig.chatClient()가
        // 먼저 등록됨. override=true로 TestAiConfig의 mock이 나중에 덮어씀 → AiConfig 메서드 미실행.
        "spring.main.allow-bean-definition-overriding=true"
    }
)
@ActiveProfiles("test")
class RagBackendApplicationTests {

    /**
     * Ollama auto-config 제외 시 없는 AI 빈들을 Mock으로 제공.
     * @Primary로 AiConfig.chatClient()(@ConditionalOnMissingBean)보다 우선.
     */
    @TestConfiguration
    static class TestAiConfig {

        @Bean
        @Primary
        ChatClient chatClient() {
            return Mockito.mock(ChatClient.class);
        }

        @Bean(name = "vlmChatClient")
        ChatClient vlmChatClient() {
            return Mockito.mock(ChatClient.class);
        }

        @Bean
        @Primary
        OllamaEmbeddingModel ollamaEmbeddingModel() {
            return Mockito.mock(OllamaEmbeddingModel.class);
        }

        @Bean
        @Primary
        S3Client s3Client() {
            return Mockito.mock(S3Client.class);
        }

        @Bean
        @Primary
        JavaMailSender javaMailSender() {
            return Mockito.mock(JavaMailSender.class);
        }

        @Bean
        @Primary
        TemplateEngine templateEngine() {
            return Mockito.mock(TemplateEngine.class);
        }
    }

    @Test
    void contextLoads() {
        // Spring context 로드 검증 (외부 서비스 없이)
    }
}
