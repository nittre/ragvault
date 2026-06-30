package com.ragvault.widget.service;

import com.ragvault.core.domain.MaskingRule;
import com.ragvault.core.repository.MaskingRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * PII 마스킹 규칙 서비스.
 *
 * getEnabledRules() 결과는 "maskingRules" 캐시에 보관.
 * 규칙 변경(save/delete) 시 evictCache() 로 무효화.
 */
@Service
@RequiredArgsConstructor
public class MaskingRuleService {

    private final MaskingRuleRepository maskingRuleRepository;

    /**
     * 전체 규칙 목록 (rule_order 정렬).
     */
    @Transactional(readOnly = true)
    public List<MaskingRule> findAll() {
        return maskingRuleRepository.findAll(
                org.springframework.data.domain.Sort.by("sortOrder"));
    }

    /**
     * 생성/수정.
     */
    @Transactional
    public MaskingRule save(MaskingRule rule) {
        MaskingRule saved = maskingRuleRepository.save(rule);
        evictCache();
        return saved;
    }

    /**
     * 삭제.
     */
    @Transactional
    public void deleteById(Long id) {
        maskingRuleRepository.deleteById(id);
        evictCache();
    }

    /**
     * enabled=true 규칙만, rule_order 오름차순. 캐시 60초 (수동 evict 방식).
     */
    @Cacheable("maskingRules")
    @Transactional(readOnly = true)
    public List<MaskingRule> getEnabledRules() {
        return maskingRuleRepository.findByEnabledTrueOrderBySortOrderAsc();
    }

    /**
     * maskingRules 캐시 전체 무효화.
     */
    @CacheEvict(value = "maskingRules", allEntries = true)
    public void evictCache() {
        // 캐시 무효화만 수행
    }
}
