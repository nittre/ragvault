package com.ragvault.widget.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 캐시 설정.
 *
 * maskingRules  — PiiMasker 에서 사용. MaskingRuleService.getEnabledRules() 결과를 60초 보관.
 * searchConfig  — SearchConfigService.getValue() 결과 캐시.
 *
 * ConcurrentMapCacheManager: 외부 의존성 없이 인-메모리 캐시.
 * TTL 은 @CacheEvict 수동 무효화로 관리 (규칙 수정 시 evict).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("maskingRules", "searchConfig", "siteKeys");
    }
}
