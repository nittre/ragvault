package com.ragvault.widget.service;

import com.ragvault.core.security.Auditable;
import com.ragvault.widget.domain.SiteKey;
import com.ragvault.widget.dto.SiteKeyConfigDto;
import com.ragvault.widget.repository.SiteKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Site-key 관리 서비스.
 *
 * - isValidKey: DB 기반 활성 키 검증 (캐시 적용)
 * - getConfig: 위젯 커스터마이징 설정 반환
 * - CRUD: admin API 에서 사용
 *
 * 캐시: "siteKeys" — ConcurrentMapCacheManager (CacheConfig)
 * TTL 은 @CacheEvict 수동 무효화로 관리.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SiteKeyService {

    private final SiteKeyRepository siteKeyRepository;

    /**
     * 활성 site-key 유효성 검증.
     * @Cacheable 로 DB 조회 결과를 캐시. 비활성 또는 미존재 키는 false.
     */
    @Cacheable(value = "siteKeys", key = "#siteKey")
    public boolean isValidKey(String siteKey) {
        return siteKeyRepository.findBySiteKeyAndActiveTrue(siteKey).isPresent();
    }

    /**
     * 캐시 무효화. 키 수정/비활성화/삭제 시 호출.
     */
    @CacheEvict(value = "siteKeys", key = "#siteKey")
    public void evictKeyCache(String siteKey) {
        log.debug("Cache evicted for siteKey={}", siteKey);
    }

    /**
     * 위젯 커스터마이징 설정 반환.
     * 비활성 키이거나 존재하지 않으면 empty.
     */
    public Optional<SiteKeyConfigDto> getConfig(String siteKey) {
        return siteKeyRepository.findBySiteKeyAndActiveTrue(siteKey)
                .map(sk -> new SiteKeyConfigDto(
                        sk.getBrandColor(),
                        sk.getBotName(),
                        sk.getGreeting(),
                        sk.getLogoUrl()
                ));
    }

    /**
     * 전체 site-key 목록 (생성일 역순).
     */
    public List<SiteKey> findAll() {
        return siteKeyRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Site-key 생성.
     */
    @Transactional
    public SiteKey create(SiteKey siteKey) {
        return siteKeyRepository.save(siteKey);
    }

    /**
     * Site-key 수정.
     * label, active, brandColor, botName, greeting, logoUrl 업데이트.
     * 수정 후 캐시 무효화.
     */
    @Transactional
    public SiteKey update(Long id, SiteKey request) {
        SiteKey existing = siteKeyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("SiteKey not found: " + id));

        existing.setLabel(request.getLabel());
        existing.setActive(request.isActive());
        existing.setBrandColor(request.getBrandColor());
        existing.setBotName(request.getBotName());
        existing.setGreeting(request.getGreeting());
        existing.setLogoUrl(request.getLogoUrl());
        existing.setUpdatedAt(Instant.now());

        SiteKey saved = siteKeyRepository.save(existing);
        evictKeyCache(saved.getSiteKey());
        return saved;
    }

    /**
     * Site-key 삭제.
     * 삭제 전 캐시 무효화. 감사 로그를 위해 삭제된 엔티티를 반환한다.
     */
    @Auditable(action = "'SITEKEY_DELETE'", targetType = "'site_key'", targetId = "#result.siteKey")
    @Transactional
    public SiteKey deleteById(Long id) {
        SiteKey existing = siteKeyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("SiteKey not found: " + id));
        evictKeyCache(existing.getSiteKey());
        siteKeyRepository.deleteById(id);
        return existing;
    }

    /**
     * 랜덤 site-key 생성.
     * 형식: pk_live_{20자리 hex}
     */
    public String generateKey() {
        return "pk_live_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
