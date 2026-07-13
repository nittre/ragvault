package com.ragservice.rag.repository;

import com.ragservice.rag.domain.AdminParamLimit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * admin_param_limits 레포지토리.
 *
 * Guard A (범위 클램핑) / Guard B (강제 고정) 파라미터 한도 조회.
 * ADR-0005: 7단계 우선순위 체인 Guard A/B.
 */
public interface AdminParamLimitRepository extends JpaRepository<AdminParamLimit, Long> {

    /** 파라미터 이름으로 한도 조회. */
    Optional<AdminParamLimit> findByParamName(String paramName);

    /** id 오름차순(등록 순서) 고정 조회 — 관리자 목록 화면에서 수정 시마다 순서가 바뀌지 않도록. */
    List<AdminParamLimit> findAllByOrderByIdAsc();
}
