package com.ragservice.rag.policy;

import com.ragvault.core.domain.DataSourceConfig;
import com.ragvault.core.policy.AccessPolicy;
import org.springframework.stereotype.Component;

/**
 * 사내 시스템 접근 정책 — 전체 허용.
 *
 * - canAccess: 사내 시스템이므로 모든 데이터소스 허용
 * - allowedAccessGroups: ["all", "internal"] — 공개 + 사내 청크 모두 허용
 */
@Component
public class FullAccessPolicy implements AccessPolicy {

    @Override
    public boolean canAccess(DataSourceConfig datasource) {
        return true;
    }

    @Override
    public String[] allowedAccessGroups() {
        return new String[]{"all", "internal"};
    }
}
