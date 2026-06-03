package com.ragservice.rag.repository;

import com.ragservice.rag.domain.MaskingRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * masking_rule 리포지토리.
 * PiiMasker 는 enabled=true 규칙을 sort_order 순으로 로드한다.
 */
public interface MaskingRuleRepository extends JpaRepository<MaskingRule, Long> {

    List<MaskingRule> findByEnabledTrueOrderBySortOrderAsc();

    List<MaskingRule> findAllByOrderBySortOrderAsc();
}
