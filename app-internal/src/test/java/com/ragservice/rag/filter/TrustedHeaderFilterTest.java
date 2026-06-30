package com.ragservice.rag.filter;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TrustedHeaderFilter 단위 테스트.
 *
 * W-3 (security-checklist.md): IP 화이트리스트 기반 X-User-* 헤더 신뢰 정책 검증.
 * ADR-0006: 외부 X-User-* 헤더 주입 차단.
 *
 * 핵심 정책:
 *   - 신뢰 IP (RFC 1918 / loopback) → X-User-* 헤더 전부 통과
 *   - 비신뢰 IP (외부 IP) → X-User-* 헤더 전부 제거
 *   - 비 X-User-* 헤더 (Authorization 등) → IP 무관 항상 통과
 */
class TrustedHeaderFilterTest {

    /** 신뢰 IP — RFC 1918 loopback. MockHttpServletRequest 기본값. */
    private static final String TRUSTED_IP = "127.0.0.1";

    /** 비신뢰 IP — TEST-NET-3 (RFC 5737). 실제 인터넷에서 라우팅되지 않는 문서용 대역. */
    private static final String UNTRUSTED_IP = "203.0.113.1";

    // ─────────────────────────────────────────────────────────────────────────
    // 1. 신뢰 IP → X-User-* 전부 통과
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("신뢰 IP(127.0.0.1)에서 온 요청: X-User-* 헤더 전부 통과")
    void trustedIp_allows_all_user_headers() throws Exception {
        TrustedHeaderFilter filter = new TrustedHeaderFilter(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/chat/completions");
        request.setRemoteAddr(TRUSTED_IP);
        request.addHeader("X-User-Email", "user@example.com");
        request.addHeader("X-User-Groups", "staff");
        request.addHeader("X-User-Role", "USER");

        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> captured.set((HttpServletRequest) req));

        HttpServletRequest wrapped = captured.get();
        assertThat(wrapped.getHeader("X-User-Email")).isEqualTo("user@example.com");
        assertThat(wrapped.getHeader("X-User-Groups")).isEqualTo("staff");
        assertThat(wrapped.getHeader("X-User-Role")).isEqualTo("USER");
    }

    @Test
    @DisplayName("신뢰 IP(10.42.x.x) — k3s 파드 네트워크: X-User-* 통과")
    void trustedIp_k3s_pod_network_allows_user_headers() throws Exception {
        TrustedHeaderFilter filter = new TrustedHeaderFilter(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/chat/completions");
        request.setRemoteAddr("10.42.1.23");   // k3s 파드 기본 CIDR
        request.addHeader("X-User-Email", "user@example.com");

        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> captured.set((HttpServletRequest) req));

        assertThat(captured.get().getHeader("X-User-Email")).isEqualTo("user@example.com");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. 비신뢰 IP → X-User-* 전부 제거
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("비신뢰 IP(외부)에서 온 요청: X-User-* 헤더 전부 제거")
    void untrustedIp_strips_all_user_headers() throws Exception {
        TrustedHeaderFilter filter = new TrustedHeaderFilter(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/chat/completions");
        request.setRemoteAddr(UNTRUSTED_IP);
        request.addHeader("X-User-Email", "attacker@evil.com");
        request.addHeader("X-User-Groups", "admin");
        request.addHeader("X-User-Role", "ADMIN");

        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> captured.set((HttpServletRequest) req));

        HttpServletRequest wrapped = captured.get();
        assertThat(wrapped.getHeader("X-User-Email")).isNull();
        assertThat(wrapped.getHeader("X-User-Groups")).isNull();
        assertThat(wrapped.getHeader("X-User-Role")).isNull();
    }

    @Test
    @DisplayName("비신뢰 IP: devBypass=true 여도 X-User-* 제거 (IP 신뢰가 우선)")
    void untrustedIp_strips_even_with_devBypass() throws Exception {
        TrustedHeaderFilter filter = new TrustedHeaderFilter(true);  // devBypass=true

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/user/param-profile");
        request.setRemoteAddr(UNTRUSTED_IP);
        request.addHeader("X-User-Email", "attacker@evil.com");

        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> captured.set((HttpServletRequest) req));

        assertThat(captured.get().getHeader("X-User-Email")).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. 일반 헤더는 IP 무관 항상 통과
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Authorization, Content-Type 등 일반 헤더: 신뢰/비신뢰 IP 무관 항상 통과")
    void non_user_headers_always_pass() throws Exception {
        for (String remoteAddr : List.of(TRUSTED_IP, UNTRUSTED_IP)) {
            TrustedHeaderFilter filter = new TrustedHeaderFilter(false);

            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
            request.setRemoteAddr(remoteAddr);
            request.addHeader("Authorization", "Bearer token123");
            request.addHeader("Content-Type", "application/json");

            AtomicReference<HttpServletRequest> captured = new AtomicReference<>();
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> captured.set((HttpServletRequest) req));

            assertThat(captured.get().getHeader("Authorization"))
                    .as("remoteAddr=%s", remoteAddr).isEqualTo("Bearer token123");
            assertThat(captured.get().getHeader("Content-Type"))
                    .as("remoteAddr=%s", remoteAddr).isEqualTo("application/json");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. 커스텀 CIDR 화이트리스트
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("커스텀 CIDR: 지정 대역만 신뢰 — 나머지 제거")
    void customCidrs_onlyTrustSpecifiedRange() throws Exception {
        // 172.20.0.0/16 만 신뢰
        TrustedHeaderFilter filter = new TrustedHeaderFilter(false, List.of("172.20.0.0/16"));

        // 신뢰 대역 내
        MockHttpServletRequest trusted = new MockHttpServletRequest("GET", "/v1/chat/completions");
        trusted.setRemoteAddr("172.20.1.5");
        trusted.addHeader("X-User-Email", "user@example.com");

        AtomicReference<HttpServletRequest> cap1 = new AtomicReference<>();
        filter.doFilter(trusted, new MockHttpServletResponse(), (req, res) -> cap1.set((HttpServletRequest) req));
        assertThat(cap1.get().getHeader("X-User-Email")).isEqualTo("user@example.com");

        // 신뢰 대역 외 (10.x.x.x — 기본 CIDR 아님)
        MockHttpServletRequest untrusted = new MockHttpServletRequest("GET", "/v1/chat/completions");
        untrusted.setRemoteAddr("10.0.0.1");
        untrusted.addHeader("X-User-Email", "attacker@evil.com");

        AtomicReference<HttpServletRequest> cap2 = new AtomicReference<>();
        filter.doFilter(untrusted, new MockHttpServletResponse(), (req, res) -> cap2.set((HttpServletRequest) req));
        assertThat(cap2.get().getHeader("X-User-Email")).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. devBypass — 로컬 개발 편의 (신뢰 IP + /api/v1/user/ 경로)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("기본 생성자: 신뢰 IP(127.0.0.1)에서 X-User-* 통과")
    void defaultConstructor_trustedIp_passes_headers() throws Exception {
        TrustedHeaderFilter filter = new TrustedHeaderFilter();  // devBypass=false, 기본 CIDR

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/chat/completions");
        // MockHttpServletRequest 기본 remoteAddr = 127.0.0.1 (신뢰 CIDR)
        request.addHeader("X-User-Email", "user@example.com");

        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> captured.set((HttpServletRequest) req));

        assertThat(captured.get().getHeader("X-User-Email")).isEqualTo("user@example.com");
    }
}
