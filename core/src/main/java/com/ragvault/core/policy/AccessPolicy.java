package com.ragvault.core.policy;

import com.ragvault.core.domain.DataSourceConfig;

/**
 * 데이터 접근 정책 포트 (default-deny).
 *
 * core는 이 인터페이스에만 의존한다.
 * app-widget과 app-internal이 각자 구현을 제공한다.
 *
 * 핵심 불변식: 외부 위젯은 사내 데이터소스·PII에 절대 닿지 않는다.
 * 단, 고객사(외부) 데이터소스 대상 text-to-sql·웹검색은 허용.
 */
public interface AccessPolicy {

    /**
     * 주어진 데이터소스에 접근 가능한지 여부.
     *
     * @param datasource 접근 대상 데이터소스 (null이면 거부)
     * @return true=허용, false=거부
     */
    boolean canAccess(DataSourceConfig datasource);

    /**
     * RAG 검색 시 허용할 access_groups 배열.
     *
     * pgvector 필터: access_groups && allowedGroups
     * 예: ["all"] — 공개 청크만 / ["all", "internal"] — 사내 청크 포함
     */
    String[] allowedAccessGroups();
}
