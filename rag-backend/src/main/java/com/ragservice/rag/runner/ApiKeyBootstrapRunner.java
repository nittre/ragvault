package com.ragservice.rag.runner;

import com.ragservice.rag.domain.ApiKey;
import com.ragservice.rag.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 최초 배포 시 API Key Bootstrap.
 *
 * BOOTSTRAP_API_KEY 환경변수가 설정돼 있고,
 * 동일 prefix 의 활성 키가 DB에 없으면 자동으로 api_keys 에 등록한다.
 *
 * - 멱등성 보장: 같은 prefix 키가 이미 존재하면 아무것도 하지 않는다.
 * - 재시작 시 중복 생성 없음.
 * - open-webui 와 공유하는 RAG_BACKEND_API_KEY 를 BOOTSTRAP_API_KEY 로 주입하면
 *   최초 배포 후 Admin UI 없이도 open-webui ↔ rag-backend 인증이 자동 구성된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyBootstrapRunner implements ApplicationRunner {

    /** ApiKeyAuthFilter.KEY_PREFIX_LENGTH 와 반드시 동일하게 유지. */
    static final int PREFIX_LENGTH = 15;

    private final ApiKeyRepository apiKeyRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${rag.bootstrap.api-key:#{null}}")
    private String bootstrapApiKey;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (bootstrapApiKey == null || bootstrapApiKey.isBlank()) {
            log.debug("rag.bootstrap.api-key 미설정 — API Key bootstrap 건너뜀");
            return;
        }

        if (bootstrapApiKey.length() < PREFIX_LENGTH) {
            log.warn("BOOTSTRAP_API_KEY 길이 부족 (최소 {}자) — bootstrap 건너뜀", PREFIX_LENGTH);
            return;
        }

        String prefix = bootstrapApiKey.substring(0, PREFIX_LENGTH);

        // 이미 같은 prefix 의 활성 키가 있으면 멱등성 보장 (재시작해도 중복 생성 안 됨)
        List<ApiKey> existing = apiKeyRepository.findActiveByPrefix(prefix, Instant.now());
        if (!existing.isEmpty()) {
            log.info("Bootstrap API key 이미 존재 (prefix={}...) — 건너뜀", prefix);
            return;
        }

        ApiKey key = new ApiKey();
        key.setName("bootstrap-service-key");
        key.setKeyPrefix(prefix);
        key.setKeyHash(passwordEncoder.encode(bootstrapApiKey));
        key.setScopes("api:chat");
        key.setActive(true);
        key.setCreatedBy("bootstrap");
        key.setCreatedAt(Instant.now());
        key.setExpiresAt(Instant.now().plus(365 * 5L, ChronoUnit.DAYS)); // 5년

        apiKeyRepository.save(key);
        log.info("Bootstrap API key 등록 완료 (prefix={}...)", prefix);
    }
}
