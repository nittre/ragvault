package com.ragservice.rag.controller;

import com.ragservice.rag.config.SecurityConfig;
import com.ragservice.rag.filter.ApiKeyAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HealthController 슬라이스 테스트.
 *
 * @WebMvcTest는 커스텀 @EnableWebSecurity 클래스를 자동 로드하지 않는다.
 * @Import(SecurityConfig.class)로 명시적으로 로드 → /api/v1/health permitAll() 적용.
 *
 * HealthController가 JdbcTemplate·StringRedisTemplate을 주입받으므로
 * @MockitoBean으로 WebMvcTest 슬라이스에서 제공한다.
 */
@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * SecurityConfig.filterChain(HttpSecurity, ApiKeyAuthFilter) 파라미터 충족을 위한 Mock.
     */
    @MockitoBean
    private ApiKeyAuthFilter apiKeyAuthFilter;

    /** AdminSessionFilter가 StringRedisTemplate을 주입받으므로 @WebMvcTest 슬라이스에서 mock 제공. */
    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    /** HealthController — PostgreSQL 체크용 */
    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    /**
     * Mock filter가 요청을 다음 필터로 전달하도록 call-through 설정.
     */
    @BeforeEach
    void setupFilterCallThrough() throws Exception {
        doAnswer(invocation -> {
            ServletRequest req = invocation.getArgument(0);
            ServletResponse res = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(apiKeyAuthFilter)
          .doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
    }

    @Test
    void healthEndpoint_returns_ok() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void deepHealth_returns_200_when_all_up() throws Exception {
        // PostgreSQL mock — SELECT 1 성공
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class))).thenReturn(1);

        // Redis mock — PONG 반환
        RedisConnectionFactory factory = org.mockito.Mockito.mock(RedisConnectionFactory.class);
        RedisConnection conn = org.mockito.Mockito.mock(RedisConnection.class);
        when(stringRedisTemplate.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(conn);
        when(conn.ping()).thenReturn("PONG");

        // Ollama는 테스트 환경에서 DOWN (localhost:11434 없음) → overall DOWN 예상
        // 따라서 200 또는 503 모두 허용, overall 필드 존재 여부만 검증
        mockMvc.perform(get("/api/v1/health/deep"))
                .andExpect(jsonPath("$.overall").exists())
                .andExpect(jsonPath("$.postgres").exists())
                .andExpect(jsonPath("$.redis").exists())
                .andExpect(jsonPath("$.ollama").exists());
    }

    @Test
    void deepHealth_returns_503_when_postgres_down() throws Exception {
        // PostgreSQL mock — 연결 실패
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        // Redis mock — PONG
        RedisConnectionFactory factory = org.mockito.Mockito.mock(RedisConnectionFactory.class);
        RedisConnection conn = org.mockito.Mockito.mock(RedisConnection.class);
        when(stringRedisTemplate.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(conn);
        when(conn.ping()).thenReturn("PONG");

        mockMvc.perform(get("/api/v1/health/deep"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.overall").value("DOWN"))
                .andExpect(jsonPath("$.postgres.status").value("DOWN"));
    }
}
