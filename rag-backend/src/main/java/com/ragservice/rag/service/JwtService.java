package com.ragservice.rag.service;

import com.ragservice.rag.domain.RagRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * ADR-0011: JJWT 0.12.x 기반 JWT 생성·검증 서비스.
 *
 * 토큰 클레임:
 *   - sub: 사용자 이메일
 *   - role: RagRole 이름 (SUPER_ADMIN / ADMIN / USER)
 *   - iat / exp: 발급 시각 / 만료 시각
 *
 * 보안:
 *   - RAG_AUTH_JWT_SECRET 는 최소 32 바이트 (openssl rand -hex 32 권장).
 *   - 키 부족 시 애플리케이션 기동 실패 (fail-fast).
 */
@Slf4j
@Service
public class JwtService {

    private final SecretKey secretKey;
    private final long expiryHours;

    public JwtService(
            @Value("${rag.auth.jwt-secret}") String secret,
            @Value("${rag.auth.jwt-expiry-hours:8}") long expiryHours) {

        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("RAG_AUTH_JWT_SECRET 환경변수가 설정되지 않았습니다.");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.expiryHours = expiryHours;
    }

    public String generateToken(String email, RagRole role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(email)
                .claim("role", role.name())
                .issuedAt(new Date(now))
                .expiration(new Date(now + expiryHours * 3_600_000L))
                .signWith(secretKey)
                .compact();
    }

    /**
     * @throws JwtException 서명 불일치·만료·형식 오류 시
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long expirySeconds() {
        return expiryHours * 3600L;
    }
}
