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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * IntentClassifierService 단위 테스트.
 *
 * LL-0006: @TestConfiguration + @Primary 패턴 대신 MockitoExtension 으로 단순화
 * (ChatClient 는 prototype 빈이므로 @MockitoBean 사용 불가 — LL-0002 참조)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IntentClassifierServiceTest {

    @Mock
    ChatClient chatClient;

    @Mock
    ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    ChatClient.CallResponseSpec callResponseSpec;

    @Mock
    RedisTemplate<String, String> redisTemplate;

    @Mock
    ValueOperations<String, String> valueOps;

    @InjectMocks
    IntentClassifierService classifier;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 기본: 캐시 미스
        when(valueOps.get(any())).thenReturn(null);
        // ChatClient 체이닝 설정
        when(chatClient.prompt()).thenReturn(requestSpec);
        // anyString() 로 String 오버로드 명시 — system(Resource), system(Consumer) 와 구분 (Spring AI 1.0.0)
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        // 캐시 쓰기 — void 메서드는 doNothing
        doNothing().when(valueOps).set(any(), any(), any(Duration.class));
    }

    @Test
    void sqlKeyword_classifiedAsSql() {
        when(callResponseSpec.content()).thenReturn("SQL");
        QueryIntent result = classifier.classify("지난달 매출 총액은?");
        assertThat(result).isEqualTo(QueryIntent.SQL);
    }

    @Test
    void hybridKeyword_classifiedAsHybrid() {
        when(callResponseSpec.content()).thenReturn("HYBRID");
        QueryIntent result = classifier.classify("보증 만료 고객 수와 보증 정책은?");
        assertThat(result).isEqualTo(QueryIntent.HYBRID);
    }

    @Test
    void ragKeyword_classifiedAsRag() {
        when(callResponseSpec.content()).thenReturn("RAG");
        QueryIntent result = classifier.classify("A 상품 보증 기간이 얼마야?");
        assertThat(result).isEqualTo(QueryIntent.RAG);
    }

    @Test
    void unknownResponse_fallbackToRag() {
        when(callResponseSpec.content()).thenReturn("모르겠습니다");
        QueryIntent result = classifier.classify("알 수 없는 질문");
        assertThat(result).isEqualTo(QueryIntent.RAG);
    }

    @Test
    void nullResponse_fallbackToRag() {
        when(callResponseSpec.content()).thenReturn(null);
        QueryIntent result = classifier.classify("질문");
        assertThat(result).isEqualTo(QueryIntent.RAG);
    }

    @Test
    void cacheHit_noLlmCall() {
        when(valueOps.get(any())).thenReturn("SQL");
        QueryIntent result = classifier.classify("캐시된 질문");
        assertThat(result).isEqualTo(QueryIntent.SQL);
        verify(chatClient, never()).prompt();
    }

    @Test
    void cacheHit_hybrid() {
        when(valueOps.get(any())).thenReturn("HYBRID");
        QueryIntent result = classifier.classify("캐시된 HYBRID 질문");
        assertThat(result).isEqualTo(QueryIntent.HYBRID);
        verify(chatClient, never()).prompt();
    }

    @Test
    void hybridBeforeSql_inResponse() {
        // "HYBRID SQL" 같은 응답에서 HYBRID 먼저 감지되어야 함
        when(callResponseSpec.content()).thenReturn("HYBRID SQL");
        QueryIntent result = classifier.classify("복합 질문");
        assertThat(result).isEqualTo(QueryIntent.HYBRID);
    }
}
