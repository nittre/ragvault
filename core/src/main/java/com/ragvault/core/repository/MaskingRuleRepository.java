package com.ragvault.core.repository;

import com.ragvault.core.domain.MaskingRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * masking_rule 리포지토리.
 * PiiMasker 는 enabled=true 규칙을 sort_order 순으로 로드한다.
 */
public interface MaskingRuleRepository extends JpaRepository<MaskingRule, Long> {

    List<MaskingRule> findByEnabledTrueOrderBySortOrderAsc();

    List<MaskingRule> findAllByOrderBySortOrderAsc();

    List<MaskingRule> findByDatasourceIdOrderBySortOrderAsc(Integer datasourceId);

    List<MaskingRule> findByDatasourceIdIsNullOrderBySortOrderAsc();

    boolean existsByNameAndDatasourceId(String name, Integer datasourceId);

    boolean existsByNameAndDatasourceIdIsNull(String name);
}
