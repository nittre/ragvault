package com.ragservice.rag.service;
import com.ragvault.core.service.*;

import com.ragvault.core.repository.SqlExecutionLogRepository;
import com.ragvault.core.repository.SqlTableConfigRepository;
import com.ragvault.core.security.PiiMasker;
import com.ragvault.core.service.SchemaInspectorService.ColumnInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TextToSqlService 단위 테스트 — 항목 5 (self-correction 조기 중단 + 친절한 폴백).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TextToSqlServiceTest {

    @Mock SchemaInspectorService schemaInspector;
    @Mock SqlGeneratorService sqlGenerator;
    @Mock SqlValidator sqlValidator;
    @Mock SqlExecutorService sqlExecutor;
    @Mock ChatClient chatClient;
    @Mock PiiMasker piiMasker;
    @Mock ResponseRawStorageService rawStorage;
    @Mock SqlTableConfigRepository sqlTableConfigRepository;
    @Mock SqlExecutionLogRepository sqlExecutionLogRepository;
    @Mock DataSourceRouterService dataSourceRouter;
    @Mock BusinessRuleService businessRuleService;
    @Mock CsvExportService csvExportService;

    @InjectMocks
    TextToSqlService service;

    @BeforeEach
    void setUp() {
        when(dataSourceRouter.route(anyString())).thenReturn(1);
        when(schemaInspector.getSchemaForActiveTables(1))
                .thenReturn(Map.of("orders", List.of(new ColumnInfo("id", "int", false, ""))));
        when(schemaInspector.getForeignKeysForActiveTables(1)).thenReturn(List.of());
        when(sqlTableConfigRepository.findByIsActiveTrue()).thenReturn(List.of());
        when(businessRuleService.collectRelevant(anyString(), eq(1))).thenReturn("");
        when(sqlGenerator.generate(anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of("SELECT bad FROM orders"));
    }

    @Test
    void repeatedViolation_earlyStop() {
        // 같은 위반이 반복되면 2회째에서 조기 중단 (3회까지 안 감)
        when(sqlValidator.validate(anyString(), any()))
                .thenReturn(SqlValidator.ValidationResult.deny("스키마에 존재하지 않는 컬럼: bad"));

        var result = service.query("질문", "user@test.com");

        assertThat(result.denied()).isTrue();
        assertThat(result.content()).contains("구체적으로");
        verify(sqlGenerator, times(2)).generate(anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void distinctViolations_exhaustAllRetries() {
        // 매번 다른 위반이면 최대 3회(초기 + 재시도 2회)까지 시도
        when(sqlValidator.validate(anyString(), any()))
                .thenReturn(SqlValidator.ValidationResult.deny("R1"))
                .thenReturn(SqlValidator.ValidationResult.deny("R2"))
                .thenReturn(SqlValidator.ValidationResult.deny("R3"));

        var result = service.query("질문", "user@test.com");

        assertThat(result.denied()).isTrue();
        assertThat(result.content()).contains("구체적으로");
        verify(sqlGenerator, times(3)).generate(anyString(), any(), any(), any(), any(), any(), any());
    }
}
