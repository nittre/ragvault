package com.ragvault.widget.policy;

import com.ragvault.core.domain.DataSourceConfig;
import com.ragvault.core.policy.AccessPolicy;
import org.springframework.stereotype.Component;

/**
 * 위젯 접근 정책 — 외부(고객사) 데이터소스만 허용, 사내 데이터소스 차단.
 *
 * - canAccess: DataSourceConfig.isInternal = false 인 경우만 허용
 * - allowedAccessGroups: ["all"] — 공개 청크만 (사내 PII 접근 불가)
 */
@Component
public class WidgetAccessPolicy implements AccessPolicy {

    @Override
    public boolean canAccess(DataSourceConfig datasource) {
        return datasource != null && !datasource.isInternal();
    }

    @Override
    public String[] allowedAccessGroups() {
        return new String[]{"all"};
    }
}
