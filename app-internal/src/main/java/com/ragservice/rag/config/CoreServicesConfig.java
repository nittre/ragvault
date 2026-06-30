package com.ragservice.rag.config;

import com.ragvault.core.service.DataSourceEncryptionService;
import com.ragvault.core.service.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * core 모듈 서비스 중 @Value 주입이 필요한 빈 등록.
 *
 * - DataSourceEncryptionService: rag.datasource.enc-key (Base64 32바이트 AES 키)
 * - JwtService: rag.auth.jwt-secret / rag.auth.jwt-expiry-hours
 */
@Configuration
public class CoreServicesConfig {

    @Bean
    public DataSourceEncryptionService dataSourceEncryptionService(
            @Value("${rag.datasource.enc-key}") String encKey) {
        return new DataSourceEncryptionService(encKey);
    }

    @Bean
    public JwtService jwtService(
            @Value("${rag.auth.jwt-secret}") String secret,
            @Value("${rag.auth.jwt-expiry-hours:8}") long expiryHours) {
        return new JwtService(secret, expiryHours);
    }
}
