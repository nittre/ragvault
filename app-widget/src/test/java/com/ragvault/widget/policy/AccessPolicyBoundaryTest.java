package com.ragvault.widget.policy;

import com.ragvault.core.domain.DataSourceConfig;
import com.ragvault.core.policy.AccessPolicy;
import com.ragvault.core.repository.SqlExecutionLogRepository;
import com.ragvault.core.repository.SqlTableConfigRepository;
import com.ragvault.core.security.PiiMasker;
import com.ragvault.core.service.DataSourceConfigService;
import com.ragvault.core.service.DataSourceRouterService;
import com.ragvault.core.service.SchemaInspectorService;
import com.ragvault.core.service.SqlExecutorService;
import com.ragvault.core.service.SqlGeneratorService;
import com.ragvault.core.service.SqlValidator;
import com.ragvault.widget.service.TextToSqlService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AccessPolicy 경계 회귀 테스트.
 *
 * 불변식:
 * - 위젯 컨텍스트에서 사내(isInternal=true) 데이터소스 → 거부
 * - 위젯 컨텍스트에서 고객사(isInternal=false) 데이터소스 → 허용
 * - RAG 검색 access_groups: ["all"] (공개 청크만, 사내 PII 차단)
 */
class AccessPolicyBoundaryTest {

    private final WidgetAccessPolicy policy = new WidgetAccessPolicy();

    @Test
    void internalDatasource_denied() {
        DataSourceConfig internal = DataSourceConfig.builder()
                .name("internal-rag").isInternal(true)
                .host("h").port(3306).dbName("d").username("u").passwordEnc("e")
                .build();
        assertThat(policy.canAccess(internal)).isFalse();
    }

    @Test
    void externalDatasource_allowed() {
        DataSourceConfig external = DataSourceConfig.builder()
                .name("customer-shop").isInternal(false)
                .host("shop.customer.com").port(3306).dbName("shop").username("u").passwordEnc("e")
                .build();
        assertThat(policy.canAccess(external)).isTrue();
    }

    @Test
    void nullDatasource_denied() {
        assertThat(policy.canAccess(null)).isFalse();
    }

    @Test
    void allowedAccessGroups_publicOnly() {
        // 위젯은 공개(all) 청크만 — 사내 access_groups 청크 접근 불가
        assertThat(policy.allowedAccessGroups()).containsExactly("all");
    }

    // ── TextToSqlService 게이트 회귀 ────────────────────────────────────────────

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class TextToSqlGateTest {

        @Mock SchemaInspectorService schemaInspector;
        @Mock SqlGeneratorService sqlGenerator;
        @Mock SqlValidator sqlValidator;
        @Mock SqlExecutorService sqlExecutor;
        @Mock ChatClient chatClient;
        @Mock PiiMasker piiMasker;
        @Mock SqlTableConfigRepository sqlTableConfigRepository;
        @Mock SqlExecutionLogRepository sqlExecutionLogRepository;
        @Mock DataSourceRouterService dataSourceRouter;
        @Mock DataSourceConfigService dataSourceConfigService;
        @Mock AccessPolicy accessPolicy;

        @InjectMocks TextToSqlService textToSqlService;

        @Test
        void internalDatasource_sqlQueryDenied() {
            DataSourceConfig internal = DataSourceConfig.builder()
                    .id(1).name("internal-db").isInternal(true)
                    .host("h").port(3306).dbName("d").username("u").passwordEnc("e")
                    .build();
            when(dataSourceRouter.route(any())).thenReturn(1);
            when(dataSourceConfigService.findById(1)).thenReturn(internal);
            when(accessPolicy.canAccess(internal)).thenReturn(false);

            TextToSqlService.SqlQueryResult result =
                    textToSqlService.query("사내 판매 현황 조회", "user@test.com");

            assertThat(result.denied()).isTrue();
            assertThat(result.content()).contains("접근할 권한이 없습니다");
        }

        @Test
        void externalDatasource_proceedsPastGate() {
            // 게이트 통과 후 스키마 없음 → 다음 단계 거부 (게이트 자체는 허용)
            DataSourceConfig external = DataSourceConfig.builder()
                    .id(2).name("customer-shop").isInternal(false)
                    .host("h").port(3306).dbName("d").username("u").passwordEnc("e")
                    .build();
            when(dataSourceRouter.route(any())).thenReturn(2);
            when(dataSourceConfigService.findById(2)).thenReturn(external);
            when(accessPolicy.canAccess(external)).thenReturn(true);
            when(schemaInspector.getSchemaForActiveTables(2)).thenReturn(java.util.Map.of());

            TextToSqlService.SqlQueryResult result =
                    textToSqlService.query("주문 현황 조회", "user@test.com");

            // 정책 게이트는 통과 — 스키마 없음 메시지(denied는 true이지만 content 다름)
            assertThat(result.content()).doesNotContain("접근할 권한이 없습니다");
        }
    }
}
