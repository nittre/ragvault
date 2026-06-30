package com.ragservice.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

/**
 * AWS S3 설정.
 * LocalStack 로컬 개발: rag.storage.s3.endpoint-override=http://localhost:4566
 * 운영: 빈 값 (DefaultCredentialsProvider 사용)
 */
@Slf4j
@Configuration
public class AwsConfig {

    @Value("${rag.storage.s3.region:ap-northeast-2}")
    private String region;

    @Value("${rag.storage.s3.endpoint-override:}")
    private String endpointOverride;

    @Value("${rag.storage.s3.force-path-style:false}")
    private boolean forcePathStyle;

    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (!endpointOverride.isBlank()) {
            log.info("S3 endpoint override: {}", endpointOverride);
            builder.endpointOverride(URI.create(endpointOverride))
                   .forcePathStyle(forcePathStyle);
        }
        return builder.build();
    }
}
