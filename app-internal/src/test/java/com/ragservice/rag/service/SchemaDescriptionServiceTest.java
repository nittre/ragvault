package com.ragservice.rag.service;
import com.ragvault.core.service.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragvault.core.domain.SqlColumnDescription;
import com.ragvault.core.domain.SqlTableConfig;
import com.ragvault.core.repository.SqlColumnDescriptionRepository;
import com.ragvault.core.repository.SqlTableConfigRepository;
import com.ragvault.core.service.SchemaInspectorService.ColumnDetail;
import com.ragvault.core.service.SchemaInspectorService.TableInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SchemaDescriptionService 단위 테스트.
 * - COMMENT 우선: 코멘트 있는 항목은 LLM 없이 source='comment' 저장
 * - LLM 보충: 코멘트 없는 항목만 LLM 결과로 source='llm' 저장
 * - 파싱 실패 시 비파괴(COMMENT 만 저장)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchemaDescriptionServiceTest {

    @Mock ChatClient chatClient;
    @Mock SqlTableConfigRepository sqlTableConfigRepository;
    @Mock SqlColumnDescriptionRepository columnDescriptionRepository;
    @Mock RoutingEmbeddingService routingEmbeddingService;

    private SchemaDescriptionService service() {
        return new SchemaDescriptionService(chatClient, new ObjectMapper(),
                sqlTableConfigRepository, columnDescriptionRepository, routingEmbeddingService);
    }

    private TableInfo table(String name, String tableComment, ColumnDetail... cols) {
        return new TableInfo(name, tableComment, List.of(cols));
    }

    @Test
    void commentPresent_storedWithoutLlm() {
        // 모든 항목에 COMMENT 존재 → LLM 호출 없음
        SqlTableConfig config = new SqlTableConfig();
        config.setSourceTable("orders");
        when(sqlTableConfigRepository.findBySourceTableAndDatasourceId("orders", 1))
                .thenReturn(Optional.of(config));
        when(columnDescriptionRepository.findByDatasourceIdAndSourceTableAndColumnName(anyInt(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        var tables = List.of(table("orders", "주문 테이블",
                new ColumnDetail("id", "int", false, "주문 PK", true)));

        service().generateAndStoreAsync(1, tables);

        verify(chatClient, never()).prompt();
        ArgumentCaptor<SqlColumnDescription> cap = ArgumentCaptor.forClass(SqlColumnDescription.class);
        verify(columnDescriptionRepository).save(cap.capture());
        assertThat(cap.getValue().getSource()).isEqualTo("comment");
        assertThat(cap.getValue().getDescription()).isEqualTo("주문 PK");
    }

    @Test
    void commentMissing_filledByLlm() {
        SqlTableConfig config = new SqlTableConfig();
        config.setSourceTable("orders");
        when(sqlTableConfigRepository.findBySourceTableAndDatasourceId("orders", 1))
                .thenReturn(Optional.of(config));
        when(columnDescriptionRepository.findByDatasourceIdAndSourceTableAndColumnName(anyInt(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        // ChatClient 플루언트 체인 모킹
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call().content()).thenReturn(
                "[{\"table\":\"orders\",\"description\":\"주문 내역\",\"columns\":[{\"name\":\"amount\",\"description\":\"결제 금액\"}]}]");

        var tables = List.of(table("orders", "",
                new ColumnDetail("amount", "int", true, "", false)));

        service().generateAndStoreAsync(1, tables);

        // 컬럼 설명이 llm 출처로 저장됨
        ArgumentCaptor<SqlColumnDescription> cap = ArgumentCaptor.forClass(SqlColumnDescription.class);
        verify(columnDescriptionRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues()).anyMatch(
                d -> "amount".equals(d.getColumnName()) && "llm".equals(d.getSource()));
        // 테이블 설명도 저장됨
        verify(sqlTableConfigRepository).save(config);
        assertThat(config.getDescription()).isEqualTo("주문 내역");
    }

    @Test
    void llmParseFailure_nonFatal() {
        SqlTableConfig config = new SqlTableConfig();
        config.setSourceTable("orders");
        when(sqlTableConfigRepository.findBySourceTableAndDatasourceId("orders", 1))
                .thenReturn(Optional.of(config));

        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call().content()).thenReturn("잘못된 응답 (JSON 아님)");

        var tables = List.of(table("orders", "",
                new ColumnDetail("amount", "int", true, "", false)));

        // 예외 없이 통과해야 함
        service().generateAndStoreAsync(1, tables);
    }
}
