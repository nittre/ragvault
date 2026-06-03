package com.ragservice.rag.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * IntentClassifierService 정적 규칙 단위 테스트 (M4 추가 경로).
 * 정적 규칙은 LLM 호출 없이 즉시 분류되므로 ChatClient mock 불필요.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IntentClassifierStaticRulesTest {

    @Mock ChatClient chatClient;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    @InjectMocks IntentClassifierService classifier;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn(null);
    }

    @Test void images_classifiedAsImage() {
        QueryIntent result = classifier.classify("이 이미지 분석해줘", List.of("base64data=="), null);
        assertThat(result).isEqualTo(QueryIntent.IMAGE);
    }

    @Test void fileIds_classifiedAsFile() {
        QueryIntent result = classifier.classify("파일 요약해줘", null, List.of("file-uuid-123"));
        assertThat(result).isEqualTo(QueryIntent.FILE);
    }

    @Test void urlInMessage_classifiedAsUrlFetch() {
        QueryIntent result = classifier.classify("https://example.com 요약해줘", null, null);
        assertThat(result).isEqualTo(QueryIntent.URL_FETCH);
    }

    @Test void httpUrl_classifiedAsUrlFetch() {
        QueryIntent result = classifier.classify("http://news.example.com 분석해줘", null, null);
        assertThat(result).isEqualTo(QueryIntent.URL_FETCH);
    }

    @Test void imagePriority_overFile() {
        // IMAGE > FILE 우선순위
        QueryIntent result = classifier.classify("분석해줘", List.of("img=="), List.of("fileid"));
        assertThat(result).isEqualTo(QueryIntent.IMAGE);
    }

    @Test void nullLists_noStaticRule() {
        // 정적 규칙 없음 → LLM 호출 (캐시 미스, chatClient.prompt() 필요)
        // LLM mock 없으면 NPE 발생 — 정적 규칙 통과 여부만 확인
        // RAG fallback (LLM 예외 시)
        QueryIntent result = classifier.classify("일반 질문", null, null);
        // URL 없고 images/fileIds 없으면 LLM 경로 (예외 발생 → RAG fallback)
        assertThat(result).isIn(QueryIntent.RAG, QueryIntent.SQL, QueryIntent.HYBRID);
    }
}
