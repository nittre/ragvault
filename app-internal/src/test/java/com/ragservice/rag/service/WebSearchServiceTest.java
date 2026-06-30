package com.ragservice.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragvault.core.security.PiiMasker;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * WebSearchService 단위 테스트.
 *
 * SearXNG HTTP 응답은 JDK 내장 HttpServer로 시뮬레이션한다.
 * 외부 의존성 없이 테스트 가능.
 */
@ExtendWith(MockitoExtension.class)
class WebSearchServiceTest {

    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.CallResponseSpec callResponseSpec;
    @Mock PiiMasker piiMasker;
    @Mock ResponseRawStorageService rawStorage;

    @InjectMocks
    WebSearchService webSearchService;

    private HttpServer mockSearxng;
    private int mockPort;

    private static final String SEARXNG_RESPONSE = """
            {
              "results": [
                {"title": "AI 트렌드 2025", "url": "https://example.com/ai", "content": "AI 트렌드 요약"},
                {"title": "머신러닝 동향", "url": "https://example.com/ml", "content": "ML 동향 설명"}
              ]
            }
            """;

    @BeforeEach
    void setUp() throws Exception {
        // 실 ObjectMapper 주입 (JSON 파싱 검증)
        ReflectionTestUtils.setField(webSearchService, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(webSearchService, "resultCount", 5);
        ReflectionTestUtils.setField(webSearchService, "llmModel", "test-model");
    }

    @AfterEach
    void tearDown() {
        if (mockSearxng != null) mockSearxng.stop(0);
    }

    // ── SearXNG 연결 실패 ─────────────────────────────────────────────────────

    @Test
    void search_searxngUnreachable_returnsDenied() {
        ReflectionTestUtils.setField(webSearchService, "searxngUrl", "http://localhost:19999");

        WebSearchService.WebSearchResult result = webSearchService.search("테스트 질문", "user@test.com");

        assertThat(result.denied()).isTrue();
        assertThat(result.content()).contains("err_search");
        verifyNoInteractions(chatClient);
    }

    // ── SearXNG 응답 정상 + LLM 성공 ─────────────────────────────────────────

    @Test
    void search_validResults_llmSuccess_returnsMasked() throws Exception {
        startMockSearxng(200, SEARXNG_RESPONSE);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("원본 응답");
        when(rawStorage.store(anyString(), anyString(), anyString(), anyString())).thenReturn("resp-id-123");
        when(piiMasker.mask(anyString())).thenReturn("마스킹된 응답");

        WebSearchService.WebSearchResult result = webSearchService.search("최신 AI 트렌드", "user@test.com");

        assertThat(result.denied()).isFalse();
        assertThat(result.content()).isEqualTo("마스킹된 응답");
        assertThat(result.responseId()).isEqualTo("resp-id-123");
        assertThat(result.sourceUrls()).contains("https://example.com/ai");

        // ADR-0010: rawStorage가 piiMasker보다 먼저 호출되어야 함
        var inOrder = inOrder(rawStorage, piiMasker);
        inOrder.verify(rawStorage).store(eq("원본 응답"), eq("WEB_SEARCH"), anyString(), anyString());
        inOrder.verify(piiMasker).mask("원본 응답");
    }

    // ── SearXNG 응답 정상 + LLM 실패 ─────────────────────────────────────────

    @Test
    void search_llmFails_returnsDenied() throws Exception {
        startMockSearxng(200, SEARXNG_RESPONSE);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("LLM timeout"));

        WebSearchService.WebSearchResult result = webSearchService.search("질문", "user@test.com");

        assertThat(result.denied()).isTrue();
        assertThat(result.content()).contains("err_llm");
        verifyNoInteractions(piiMasker);
        verifyNoInteractions(rawStorage);
    }

    // ── SearXNG 빈 결과 ───────────────────────────────────────────────────────

    @Test
    void search_emptyResults_returnsDenied() throws Exception {
        startMockSearxng(200, "{\"results\":[]}");

        WebSearchService.WebSearchResult result = webSearchService.search("질문", "user@test.com");

        assertThat(result.denied()).isTrue();
        verifyNoInteractions(chatClient);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private void startMockSearxng(int status, String body) throws Exception {
        mockSearxng = HttpServer.create(new InetSocketAddress(0), 0);
        mockPort = mockSearxng.getAddress().getPort();
        mockSearxng.createContext("/search", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        });
        mockSearxng.start();
        ReflectionTestUtils.setField(webSearchService, "searxngUrl", "http://localhost:" + mockPort);
    }
}
