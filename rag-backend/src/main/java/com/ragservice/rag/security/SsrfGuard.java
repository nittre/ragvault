package com.ragservice.rag.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * SSRF 방어 가드.
 *
 * 차단:
 * - http/https 외 스킴
 * - RFC 1918 사설 IP (10/172.16-31/192.168)
 * - loopback (127.x, ::1)
 * - link-local (169.254.x — AWS metadata 포함)
 * - IPv6 ULA (fc00::/7), link-local (fe80::/10)
 *
 * DNS resolve 후 IP 검증 (DNS rebinding 방어).
 * Redirect hop마다 재검증 필요.
 *
 * requirements/10-multimodal-files-url.md 섹션 7
 */
@Slf4j
@Component
public class SsrfGuard {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    public record ValidationResult(boolean allowed, String reason) {
        public static ValidationResult allow() { return new ValidationResult(true, null); }
        public static ValidationResult deny(String reason) { return new ValidationResult(false, reason); }
    }

    public ValidationResult validate(String urlStr) {
        if (urlStr == null || urlStr.isBlank()) {
            return ValidationResult.deny("URL이 비어 있습니다");
        }
        URI uri;
        try {
            uri = new URI(urlStr);
        } catch (URISyntaxException e) {
            return ValidationResult.deny("유효하지 않은 URL: " + e.getMessage());
        }
        if (uri.getScheme() == null || !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase())) {
            return ValidationResult.deny("허용되지 않는 스킴: " + uri.getScheme());
        }
        String host = uri.getHost();
        if (host == null) {
            return ValidationResult.deny("호스트 없음");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (isPrivateOrReserved(addr)) {
                    log.warn("SSRF 차단: host={} ip={}", host, addr.getHostAddress());
                    return ValidationResult.deny("내부 IP 접근 차단: " + addr.getHostAddress());
                }
            }
        } catch (UnknownHostException e) {
            return ValidationResult.deny("DNS 조회 실패: " + host);
        }
        return ValidationResult.allow();
    }

    /** IP가 사설/예약 주소인지 확인. 테스트에서 직접 호출 가능. */
    public boolean isPrivateOrReserved(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()) {
            return true;
        }
        byte[] raw = addr.getAddress();
        if (raw.length == 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            if (b0 == 169 && b1 == 254) return true;   // 169.254.x.x (AWS metadata)
            if (b0 == 10) return true;                  // 10.x.x.x
            if (b0 == 172 && b1 >= 16 && b1 <= 31) return true;  // 172.16-31.x.x
            if (b0 == 192 && b1 == 168) return true;   // 192.168.x.x
            if (b0 == 0) return true;                   // 0.x.x.x
        }
        if (raw.length == 16) {
            int b0 = raw[0] & 0xFF;
            if ((b0 & 0xFE) == 0xFC) return true; // fc00::/7 ULA
        }
        return false;
    }
}
