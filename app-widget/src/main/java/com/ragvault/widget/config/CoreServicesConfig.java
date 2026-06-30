package com.ragvault.widget.config;

import com.ragvault.core.service.DataSourceEncryptionService;
import com.ragvault.core.service.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * core 모듈 서비스 중 @Value 주입이 필요한 빈 등록.
 *
 * - DataSourceEncryptionService: widget.encryption.key (Base64 32바이트 AES 키)
 * - JwtService: widget.auth.jwt-secret / widget.auth.jwt-expiry-hours
 */
@Configuration
public class CoreServicesConfig {

    @Bean
    public DataSourceEncryptionService dataSourceEncryptionService(
            @Value("${widget.encryption.key}") String encKey) {
        // widget은 raw key 바이트(UTF-8 기반 32바이트 pad)를 사용하므로
        // Base64 디코드 전 Base64 인코딩이 필요
        // 기존 widget DataSourceEncryptionService는 key를 copyOf(32)로 처리했으나
        // core 버전은 Base64 디코드를 사용함.
        // 호환성을 위해 key를 Base64로 인코딩해 전달.
        byte[] keyBytes = encKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] key32 = java.util.Arrays.copyOf(keyBytes, 32);
        String base64Key = java.util.Base64.getEncoder().encodeToString(key32);
        return new DataSourceEncryptionService(base64Key);
    }

    @Bean
    public JwtService jwtService(
            @Value("${widget.auth.jwt-secret}") String secret,
            @Value("${widget.auth.jwt-expiry-hours:8}") long expiryHours) {
        return new JwtService(secret, expiryHours);
    }
}
