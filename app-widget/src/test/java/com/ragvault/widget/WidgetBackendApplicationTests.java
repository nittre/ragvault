package com.ragvault.widget;

import com.ragvault.widget.service.KnowledgeIngestionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * 컨텍스트 로드 테스트.
 *
 * LL-0002(rag-practice): Spring Boot 3.5 + Spring AI 1.0.0 호환성.
 * - Ollama auto-config 제외 (DB/Ollama 없이 컴파일 검증)
 * - @TestConfiguration + @Primary 로 mock bean 제공
 * - @ConditionalOnMissingBean 감지를 위해 spring.main.allow-bean-definition-overriding=true
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.autoconfigure.exclude=" +
            "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration," +
            "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration"
    }
)
@ActiveProfiles("test")
class WidgetBackendApplicationTests {

    @TestConfiguration
    static class TestAiConfig {

        @Bean
        @Primary
        ChatClient chatClient() {
            return Mockito.mock(ChatClient.class);
        }

        @Bean
        @Primary
        OllamaEmbeddingModel ollamaEmbeddingModel() {
            return Mockito.mock(OllamaEmbeddingModel.class);
        }

        @Bean
        @Primary
        KnowledgeIngestionService knowledgeIngestionService() {
            return Mockito.mock(KnowledgeIngestionService.class);
        }
    }

    @Test
    void contextLoads() {
        // Spring context 로드 검증 (Ollama/DB 없이)
    }
}
