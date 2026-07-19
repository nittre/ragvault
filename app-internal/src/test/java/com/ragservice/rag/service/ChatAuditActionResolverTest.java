package com.ragservice.rag.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatAuditActionResolver 의 intent→audit_log.action 매핑 검증.
 *
 * HYBRID/WEB_SEARCH를 CHAT으로 뭉개면 사용량 통계(AdminUsageStatsController)에서 사라지는
 * 회귀를 막기 위한 테스트. 이전에는 ChatController.resolveAction()에 대한 테스트였으나,
 * 매핑 로직이 이 빈으로 이관되면서 함께 이동했다.
 */
class ChatAuditActionResolverTest {

    private final ChatAuditActionResolver resolver = new ChatAuditActionResolver();

    @Test
    void sqlIntent_mapsToSqlQuery() {
        assertThat(resolver.resolve("SQL")).isEqualTo("SQL_QUERY");
    }

    @Test
    void fileIntent_mapsToFileUpload() {
        assertThat(resolver.resolve("FILE")).isEqualTo("FILE_UPLOAD");
    }

    @Test
    void ragIntent_mapsToRag() {
        assertThat(resolver.resolve("RAG")).isEqualTo("RAG");
    }

    @Test
    void hybridIntent_mapsToHybrid_notChat() {
        assertThat(resolver.resolve("HYBRID")).isEqualTo("HYBRID");
    }

    @Test
    void webSearchIntent_mapsToWebSearch_notChat() {
        assertThat(resolver.resolve("WEB_SEARCH")).isEqualTo("WEB_SEARCH");
    }

    @Test
    void imageIntent_mapsToOther() {
        assertThat(resolver.resolve("IMAGE")).isEqualTo("OTHER");
    }
}
