package com.ragservice.rag.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

/**
 * X-User-* 헤더를 외부에서 주입하는 것을 차단하는 필터.
 *
 * ADR-0006: 사용자 식별 헤더는 Open WebUI 백엔드 프록시가 주입.
 * 외부 요청에서 해당 헤더를 직접 전송하면 제거한다.
 *
 * W-3 (security-checklist.md): trustedProxyCidrs 기반 코드 레벨 IP 화이트리스트 추가.
 *   - 신뢰 IP(k3s 파드 네트워크 등)에서 온 요청은 X-User-* 헤더를 통과시킨다.
 *   - 신뢰 IP가 아닌 요청은 X-User-* 헤더를 모두 제거한다.
 *   - 기본값: RFC 1918 사설 대역 + loopback (k3s 내부 파드 간 통신 커버).
 *   - 설정: rag.security.trusted-proxy-cidrs (application.yml).
 *
 * M5-3: devBypass=true 이면 /api/v1/user/** 의 X-User-Email 추가 통과 허용 (로컬 개발용).
 * FilterRegistrationBean order=1 로 Spring Security 앞에서 실행.
 */
@Slf4j
public class TrustedHeaderFilter extends OncePerRequestFilter {

    /** devBypass=true 여부 (application.yml: rag.admin.dev-bypass). */
    private final boolean devBypass;

    /**
     * 신뢰 프록시 CIDR 목록.
     * 이 대역에서 온 요청은 X-User-* 헤더를 신뢰한다.
     * 기본값: RFC 1918 전체 + loopback.
     */
    private final List<CidrRange> trustedCidrs;

    /** 기본 신뢰 CIDR — k3s 파드 네트워크(10.42.0.0/16 포함)를 커버하는 RFC 1918 전체. */
    private static final List<String> DEFAULT_TRUSTED_CIDRS = List.of(
            "127.0.0.0/8",       // loopback
            "10.0.0.0/8",        // RFC 1918 — k3s 파드 기본(10.42.x.x) 포함
            "172.16.0.0/12",     // RFC 1918
            "192.168.0.0/16"     // RFC 1918
    );

    /** 항상 제거하는 헤더 (경로·IP 무관). */
    private static final Set<String> TRUSTED_HEADERS = Set.of(
            "x-user-email",
            "x-user-id",
            "x-user-groups",
            "x-user-role"
    );

    /** X-User-Email 을 통과시키는 경로 접두사 (devBypass 전용). */
    private static final String USER_API_PREFIX = "/api/v1/user/";

    // ── 생성자 ──────────────────────────────────────────────────────────────────

    public TrustedHeaderFilter(boolean devBypass, List<String> trustedProxyCidrs) {
        this.devBypass = devBypass;
        this.trustedCidrs = parseCidrs(
                (trustedProxyCidrs == null || trustedProxyCidrs.isEmpty())
                        ? DEFAULT_TRUSTED_CIDRS
                        : trustedProxyCidrs
        );
        log.info("TrustedHeaderFilter initialized — devBypass={}, trustedCidrs={}",
                devBypass, trustedProxyCidrs == null ? DEFAULT_TRUSTED_CIDRS : trustedProxyCidrs);
    }

    /** devBypass 만 받는 생성자 — 기본 CIDR 사용. */
    public TrustedHeaderFilter(boolean devBypass) {
        this(devBypass, DEFAULT_TRUSTED_CIDRS);
    }

    /** 하위 호환: 인수 없이 생성 시 devBypass=false + 기본 CIDR. */
    public TrustedHeaderFilter() {
        this(false, DEFAULT_TRUSTED_CIDRS);
    }

    // ── 필터 로직 ───────────────────────────────────────────────────────────────

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String remoteAddr = request.getRemoteAddr();
        boolean fromTrustedProxy = isTrustedProxy(remoteAddr);

        if (!fromTrustedProxy) {
            // 신뢰 IP 아님 — 모든 X-User-* 헤더 제거
            log.debug("Untrusted remote IP [{}] — stripping all X-User-* headers", remoteAddr);
            filterChain.doFilter(new HeaderStrippingWrapper(request, false, false), response);
            return;
        }

        // 신뢰 IP에서 온 요청 — X-User-* 헤더 통과 허용
        // devBypass: /api/v1/user/ 경로에서 X-User-Email 추가 허용 (로컬 개발)
        boolean allowUserEmail = devBypass && request.getRequestURI().startsWith(USER_API_PREFIX);
        filterChain.doFilter(new HeaderStrippingWrapper(request, true, allowUserEmail), response);
    }

    // ── IP 신뢰 검증 ────────────────────────────────────────────────────────────

    private boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) return false;
        try {
            InetAddress addr = InetAddress.getByName(remoteAddr);
            for (CidrRange cidr : trustedCidrs) {
                if (cidr.contains(addr)) return true;
            }
        } catch (UnknownHostException e) {
            log.warn("Cannot resolve remote address [{}] — treating as untrusted", remoteAddr);
        }
        return false;
    }

    private static List<CidrRange> parseCidrs(List<String> cidrs) {
        return cidrs.stream()
                .map(cidr -> {
                    try {
                        return CidrRange.parse(cidr);
                    } catch (Exception e) {
                        log.error("Invalid CIDR [{}] in trusted-proxy-cidrs — skipping: {}", cidr, e.getMessage());
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    // ── 헤더 제거 Wrapper ───────────────────────────────────────────────────────

    private static class HeaderStrippingWrapper extends HttpServletRequestWrapper {

        /** 신뢰 프록시에서 온 요청이면 X-User-* 전체 통과. */
        private final boolean trustedProxy;
        /** devBypass 모드에서 /api/v1/user/ 경로 X-User-Email 추가 통과. */
        private final boolean allowUserEmail;

        HeaderStrippingWrapper(HttpServletRequest request, boolean trustedProxy, boolean allowUserEmail) {
            super(request);
            this.trustedProxy = trustedProxy;
            this.allowUserEmail = allowUserEmail;
        }

        @Override
        public String getHeader(String name) {
            if (isBlocked(name)) return null;
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (isBlocked(name)) return Collections.emptyEnumeration();
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames())
                    .stream()
                    .filter(name -> !isBlocked(name))
                    .toList();
            return Collections.enumeration(names);
        }

        private boolean isBlocked(String name) {
            if (name == null) return false;
            String lower = name.toLowerCase();
            if (!TRUSTED_HEADERS.contains(lower)) return false;   // X-User-* 아님 — 통과
            if (trustedProxy) return false;                        // 신뢰 프록시 — 모두 통과
            // 비신뢰 프록시: devBypass 모드에서 x-user-email 만 예외
            return !(allowUserEmail && "x-user-email".equals(lower));
        }
    }

    // ── CIDR 유틸리티 ───────────────────────────────────────────────────────────

    /**
     * 단순 CIDR 범위 매처 (외부 의존성 없음).
     * IPv4 only — Phase 0 인프라는 IPv4 전용.
     */
    record CidrRange(byte[] network, int prefixLen) {

        static CidrRange parse(String cidr) {
            String[] parts = cidr.split("/");
            if (parts.length != 2) throw new IllegalArgumentException("Invalid CIDR: " + cidr);
            try {
                InetAddress addr = InetAddress.getByName(parts[0]);
                int prefix = Integer.parseInt(parts[1].trim());
                return new CidrRange(addr.getAddress(), prefix);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid CIDR address: " + cidr, e);
            }
        }

        boolean contains(InetAddress addr) {
            byte[] addrBytes = addr.getAddress();
            if (addrBytes.length != network.length) return false;  // IPv4/IPv6 불일치
            int fullBytes = prefixLen / 8;
            int remainBits = prefixLen % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addrBytes[i] != network[i]) return false;
            }
            if (remainBits > 0 && fullBytes < network.length) {
                int mask = 0xFF & (0xFF << (8 - remainBits));
                return (addrBytes[fullBytes] & mask) == (network[fullBytes] & mask);
            }
            return true;
        }
    }
}
