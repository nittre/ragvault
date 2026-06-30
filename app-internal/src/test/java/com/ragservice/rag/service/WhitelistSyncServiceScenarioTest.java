package com.ragservice.rag.service;
import com.ragvault.core.service.*;

import com.ragservice.rag.domain.*;
import com.ragvault.core.domain.*;
import com.ragservice.rag.repository.*;
import com.ragvault.core.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WhitelistSyncService 시나리오별 통합 테스트.
 *
 * 실제 운영에서 발생하는 DDL 변경 시나리오를 end-to-end로 검증.
 * 각 시나리오는 상태 설정 → DDL 이벤트 발생 → 결과 검증 구조.
 */
@ExtendWith(MockitoExtension.class)
class WhitelistSyncServiceScenarioTest {

    @Mock SyncModeConfigRepository syncModeConfigRepository;
    @Mock SqlTableConfigRepository sqlTableConfigRepository;
    @Mock RagTableConfigRepository ragTableConfigRepository;
    @Mock DdlEventRepository ddlEventRepository;
    @Mock SchemaInspectorService schemaInspector;
    @Mock SensitivityAnalysisService sensitivityAnalysisService;
    @Mock RagColumnSuggestionService ragColumnSuggestionService;

    @InjectMocks WhitelistSyncService svc;

    private static final int DS_ID = 42;

    private void givenSqlAutoOn()  { givenMode("sql", true); }
    private void givenRagAutoOn()  { givenMode("rag", true); }
    private void givenSqlAutoOff() { givenMode("sql", false); }
    private void givenRagAutoOff() { givenMode("rag", false); }

    private void givenMode(String type, boolean on) {
        SyncModeConfig cfg = SyncModeConfig.builder()
                .datasourceId(DS_ID).tableType(type)
                .autoSyncEnabled(on)
                .disabledAt(on ? null : Instant.now().minusSeconds(1800))
                .build();
        when(syncModeConfigRepository.findByDatasourceIdAndTableType(DS_ID, type))
                .thenReturn(Optional.of(cfg));
    }

    @BeforeEach
    void defaultSchemaEmpty() {
        lenient().when(schemaInspector.getAllTablesWithSchema(DS_ID)).thenReturn(List.of());
    }

    // ── 시나리오 1: DROP TABLE → SQL + RAG 둘 다 hard delete ──────────────────

    @Test
    void scenario_dropTable_bothAutoOn_hardDeletesBoth() {
        givenSqlAutoOn();
        givenRagAutoOn();

        svc.onDdl(DS_ID, "DROP TABLE orders");

        verify(sqlTableConfigRepository).deleteBySourceTableAndDatasourceId("orders", DS_ID);
        verify(ragTableConfigRepository).deleteBySourceTableAndDatasourceId("orders", DS_ID);
    }

    // ── 시나리오 2: DROP TABLE → SQL AUTO ON, RAG AUTO OFF → SQL만 삭제 ───────

    @Test
    void scenario_dropTable_onlySqlAutoOn_onlySqlDeleted() {
        givenSqlAutoOn();
        givenRagAutoOff();

        svc.onDdl(DS_ID, "DROP TABLE orders");

        verify(sqlTableConfigRepository).deleteBySourceTableAndDatasourceId("orders", DS_ID);
        verify(ragTableConfigRepository, never()).deleteBySourceTableAndDatasourceId(any(), any());
    }

    // ── 시나리오 3: CREATE TABLE → 신규 등록 + LLM 분석 트리거 ──────────────

    @Test
    void scenario_createTable_registersWithPendingAndTriggersLlm() {
        givenSqlAutoOn();
        givenRagAutoOff();
        when(sqlTableConfigRepository.findBySourceTableAndDatasourceId("products", DS_ID))
                .thenReturn(Optional.empty());
        when(sqlTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.onDdl(DS_ID, "CREATE TABLE products");

        ArgumentCaptor<SqlTableConfig> cap = ArgumentCaptor.forClass(SqlTableConfig.class);
        verify(sqlTableConfigRepository).save(cap.capture());
        assertThat(cap.getValue().getSourceTable()).isEqualTo("products");
        assertThat(cap.getValue().getLlmStatus()).isEqualTo("pending");
        assertThat(cap.getValue().isActive()).isTrue();
        verify(sensitivityAnalysisService).analyzeAndUpdateAsync(eq(DS_ID), eq(List.of("products")), any());
    }

    // ── 시나리오 4: CREATE TABLE → 기존 비활성 레코드 있음 → upsert(재활성화) ─

    @Test
    void scenario_createTable_existingInactive_reactivatesWithoutDuplicateInsert() {
        givenSqlAutoOn();
        givenRagAutoOff();

        SqlTableConfig existing = new SqlTableConfig();
        existing.setSourceTable("products");
        existing.setDatasourceId(DS_ID);
        existing.setActive(false);
        existing.setLlmStatus("done");

        when(sqlTableConfigRepository.findBySourceTableAndDatasourceId("products", DS_ID))
                .thenReturn(Optional.of(existing));
        when(sqlTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.onDdl(DS_ID, "CREATE TABLE products");

        // save 1회만 (INSERT 아닌 UPDATE)
        verify(sqlTableConfigRepository, times(1)).save(any());
        ArgumentCaptor<SqlTableConfig> cap = ArgumentCaptor.forClass(SqlTableConfig.class);
        verify(sqlTableConfigRepository).save(cap.capture());
        assertThat(cap.getValue().isActive()).isTrue();
        assertThat(cap.getValue().getLlmStatus()).isEqualTo("pending");
    }

    // ── 시나리오 5: RENAME TABLE → old 삭제 + new 신규 등록 ─────────────────

    @Test
    void scenario_renameTable_deletesOldAndRegistersNew() {
        givenSqlAutoOn();
        givenRagAutoOff();
        when(sqlTableConfigRepository.findBySourceTableAndDatasourceId("orders_new", DS_ID))
                .thenReturn(Optional.empty());
        when(sqlTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.onDdl(DS_ID, "RENAME TABLE orders TO orders_new");

        verify(sqlTableConfigRepository).deleteBySourceTableAndDatasourceId("orders", DS_ID);
        ArgumentCaptor<SqlTableConfig> cap = ArgumentCaptor.forClass(SqlTableConfig.class);
        verify(sqlTableConfigRepository).save(cap.capture());
        assertThat(cap.getValue().getSourceTable()).isEqualTo("orders_new");
    }

    // ── 시나리오 6: ALTER DROP COLUMN → allowedColumns에서 해당 컬럼 제거 ────

    @Test
    void scenario_alterDropColumn_removesFromAllowedColumns() {
        givenSqlAutoOn();
        givenRagAutoOff();

        SqlTableConfig cfg = new SqlTableConfig();
        cfg.setSourceTable("users");
        cfg.setDatasourceId(DS_ID);
        cfg.setAllowedColumns(new String[]{"name", "email", "phone"});

        when(sqlTableConfigRepository.findBySourceTableAndDatasourceId("users", DS_ID))
                .thenReturn(Optional.of(cfg));
        when(sqlTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.onDdl(DS_ID, "ALTER TABLE users DROP COLUMN email");

        ArgumentCaptor<SqlTableConfig> cap = ArgumentCaptor.forClass(SqlTableConfig.class);
        verify(sqlTableConfigRepository).save(cap.capture());
        assertThat(cap.getValue().getAllowedColumns())
                .containsExactlyInAnyOrder("name", "phone")
                .doesNotContain("email");
    }

    // ── 시나리오 7: RAG content_columns 마지막 컬럼 DROP → hard delete ────────

    @Test
    void scenario_ragAlterDropLastContentCol_hardDeletes() {
        givenSqlAutoOff();
        givenRagAutoOn();

        RagTableConfig cfg = new RagTableConfig();
        cfg.setSourceTable("articles");
        cfg.setDatasourceId(DS_ID);
        cfg.setPkColumn("id");
        cfg.setContentColumnsJson("body");   // 유일한 content 컬럼
        cfg.setMetadataColumnsJson("created_at");

        when(ragTableConfigRepository.findBySourceTableAndDatasourceId("articles", DS_ID))
                .thenReturn(Optional.of(cfg));

        svc.onDdl(DS_ID, "ALTER TABLE articles DROP COLUMN body");

        verify(ragTableConfigRepository).delete(cfg);
        verify(ragTableConfigRepository, never()).save(any());
    }

    // ── 시나리오 8: RAG PK 컬럼 DROP → hard delete ────────────────────────────

    @Test
    void scenario_ragAlterDropPkCol_hardDeletes() {
        givenSqlAutoOff();
        givenRagAutoOn();

        RagTableConfig cfg = new RagTableConfig();
        cfg.setSourceTable("articles");
        cfg.setDatasourceId(DS_ID);
        cfg.setPkColumn("id");
        cfg.setContentColumnsJson("title,body");
        cfg.setMetadataColumnsJson("");

        when(ragTableConfigRepository.findBySourceTableAndDatasourceId("articles", DS_ID))
                .thenReturn(Optional.of(cfg));

        svc.onDdl(DS_ID, "ALTER TABLE articles DROP COLUMN id");

        verify(ragTableConfigRepository).delete(cfg);
    }

    // ── 시나리오 9: RAG content_columns 여러 개 중 하나 DROP → 나머지 유지 ───

    @Test
    void scenario_ragAlterDropOneOfManyContentCols_keepsRest() {
        givenSqlAutoOff();
        givenRagAutoOn();

        RagTableConfig cfg = new RagTableConfig();
        cfg.setSourceTable("posts");
        cfg.setDatasourceId(DS_ID);
        cfg.setPkColumn("id");
        cfg.setContentColumnsJson("title,summary,body");
        cfg.setMetadataColumnsJson("created_at");

        when(ragTableConfigRepository.findBySourceTableAndDatasourceId("posts", DS_ID))
                .thenReturn(Optional.of(cfg));
        when(ragTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.onDdl(DS_ID, "ALTER TABLE posts DROP COLUMN summary");

        ArgumentCaptor<RagTableConfig> cap = ArgumentCaptor.forClass(RagTableConfig.class);
        verify(ragTableConfigRepository).save(cap.capture());
        assertThat(cap.getValue().getContentColumnsJson()).doesNotContain("summary");
        assertThat(cap.getValue().getContentColumnsJson()).contains("title");
        assertThat(cap.getValue().getContentColumnsJson()).contains("body");
        verify(ragTableConfigRepository, never()).delete(any());
    }

    // ── 시나리오 10: AUTO OFF → 모든 DDL 무시 ────────────────────────────────

    @Test
    void scenario_autoOff_allDdlIgnored() {
        givenSqlAutoOff();
        givenRagAutoOff();

        svc.onDdl(DS_ID, "DROP TABLE orders");
        svc.onDdl(DS_ID, "CREATE TABLE products");
        svc.onDdl(DS_ID, "ALTER TABLE users DROP COLUMN email");

        verifyNoInteractions(sqlTableConfigRepository, ragTableConfigRepository,
                sensitivityAnalysisService, ragColumnSuggestionService);
    }

    // ── 시나리오 11: Replay 멱등성 — 동일 이벤트 두 번 처리 ─────────────────

    @Test
    void scenario_replay_idempotent_secondRunSkips() {
        SyncModeConfig modeCfg = SyncModeConfig.builder()
                .datasourceId(DS_ID).tableType("sql").autoSyncEnabled(false)
                .disabledAt(Instant.now().minusSeconds(3600)).build();
        when(syncModeConfigRepository.findByDatasourceIdAndTableType(DS_ID, "sql"))
                .thenReturn(Optional.of(modeCfg));

        DdlEvent event = DdlEvent.builder()
                .sqlQuery("DROP TABLE orders")
                .whitelistAppliedSqlAt(Instant.now())  // 이미 처리됨
                .build();
        when(ddlEventRepository.findByDatasourceIdAndCreatedAtAfterOrderByCreatedAtAsc(eq(DS_ID), any()))
                .thenReturn(List.of(event));

        WhitelistSyncService.ReplayResult result = svc.replay(DS_ID, "sql");

        assertThat(result.applied()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(sqlTableConfigRepository, never()).deleteBySourceTableAndDatasourceId(any(), any());
    }

    // ── 시나리오 12: Replay 순서 보장 — DROP 후 CREATE 순서 ─────────────────

    @Test
    void scenario_replay_orderedDropThenCreate_registersNew() {
        SyncModeConfig modeCfg = SyncModeConfig.builder()
                .datasourceId(DS_ID).tableType("sql").autoSyncEnabled(false)
                .disabledAt(Instant.now().minusSeconds(3600)).build();
        when(syncModeConfigRepository.findByDatasourceIdAndTableType(DS_ID, "sql"))
                .thenReturn(Optional.of(modeCfg));

        DdlEvent drop = DdlEvent.builder().sqlQuery("DROP TABLE orders").build();
        DdlEvent create = DdlEvent.builder().sqlQuery("CREATE TABLE orders").build();
        when(ddlEventRepository.findByDatasourceIdAndCreatedAtAfterOrderByCreatedAtAsc(eq(DS_ID), any()))
                .thenReturn(List.of(drop, create));

        // replay()는 applySqlAction 직접 호출 — rag 모드 조회 없음
        // schemaInspector는 @BeforeEach lenient stub이 커버함
        when(sqlTableConfigRepository.deleteBySourceTableAndDatasourceId("orders", DS_ID)).thenReturn(1);
        when(sqlTableConfigRepository.findBySourceTableAndDatasourceId("orders", DS_ID))
                .thenReturn(Optional.empty());
        when(sqlTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(ddlEventRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        WhitelistSyncService.ReplayResult result = svc.replay(DS_ID, "sql");

        assertThat(result.applied()).isEqualTo(2);
        // DROP 실행
        verify(sqlTableConfigRepository).deleteBySourceTableAndDatasourceId("orders", DS_ID);
        // CREATE 실행
        verify(sqlTableConfigRepository).findBySourceTableAndDatasourceId("orders", DS_ID);
        verify(sqlTableConfigRepository).save(any());
    }

    // ── 시나리오 13: 백틱·스키마 prefix 포함 DDL 처리 ───────────────────────

    @Test
    void scenario_backtickAndSchemaPrefixDdl_parsedCorrectly() {
        givenSqlAutoOn();
        givenRagAutoOff();

        svc.onDdl(DS_ID, "DROP TABLE `mydb`.`orders`");

        verify(sqlTableConfigRepository).deleteBySourceTableAndDatasourceId("orders", DS_ID);
    }

    // ── 시나리오 14: 알 수 없는 DDL → 아무 변경 없음 ────────────────────────

    @Test
    void scenario_unknownDdl_noChanges() {
        // UNKNOWN DDL은 parseDdl 후 즉시 return — 모드 조회 자체가 발생하지 않음
        svc.onDdl(DS_ID, "ALTER TABLE orders ENGINE=InnoDB");

        verifyNoInteractions(syncModeConfigRepository, sqlTableConfigRepository, ragTableConfigRepository);
    }

    // ── 시나리오 15: 멀티 절 ALTER TABLE → 두 액션 모두 적용 ─────────────────

    @Test
    void scenario_multiClauseAlter_appliesBothActions() {
        givenSqlAutoOn();
        givenRagAutoOff();

        SqlTableConfig cfg = new SqlTableConfig();
        cfg.setSourceTable("orders");
        cfg.setDatasourceId(DS_ID);
        cfg.setAllowedColumns(new String[]{"name", "email"});

        when(sqlTableConfigRepository.findBySourceTableAndDatasourceId("orders", DS_ID))
                .thenReturn(java.util.Optional.of(cfg));
        when(sqlTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.onDdl(DS_ID, "ALTER TABLE orders DROP COLUMN email, ADD COLUMN phone VARCHAR(20)");

        // DROP COLUMN email
        ArgumentCaptor<SqlTableConfig> cap = ArgumentCaptor.forClass(SqlTableConfig.class);
        verify(sqlTableConfigRepository, atLeastOnce()).save(cap.capture());
        boolean emailRemoved = cap.getAllValues().stream()
                .anyMatch(c -> c.getAllowedColumns() != null &&
                               java.util.Arrays.stream(c.getAllowedColumns())
                                       .noneMatch("email"::equals));
        assertThat(emailRemoved).isTrue();

        // ADD COLUMN phone → LLM pending
        boolean phonePending = cap.getAllValues().stream()
                .anyMatch(c -> "pending".equals(c.getLlmStatus()));
        assertThat(phonePending).isTrue();
    }

    // ── 시나리오 16: RENAME TABLE 멀티 쌍 → 두 쌍 모두 처리 ─────────────────

    @Test
    void scenario_renameTwoTables_processesBothPairs() {
        givenSqlAutoOn();
        givenRagAutoOff();

        when(sqlTableConfigRepository.findBySourceTableAndDatasourceId(any(), eq(DS_ID)))
                .thenReturn(java.util.Optional.empty());
        when(sqlTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.onDdl(DS_ID, "RENAME TABLE orders TO orders_v2, users TO members");

        verify(sqlTableConfigRepository).deleteBySourceTableAndDatasourceId("orders", DS_ID);
        verify(sqlTableConfigRepository).deleteBySourceTableAndDatasourceId("users", DS_ID);
    }

    // ── 시나리오 17: onDdl(DdlEvent) 이중 호출 → 멱등성 보장 ─────────────────

    @Test
    void scenario_onDdlEvent_calledTwice_processedOnlyOnce() {
        givenSqlAutoOn();
        givenRagAutoOff();

        DdlEvent event = DdlEvent.builder().sqlQuery("DROP TABLE orders").build();

        when(sqlTableConfigRepository.deleteBySourceTableAndDatasourceId("orders", DS_ID)).thenReturn(1);
        when(ddlEventRepository.save(any())).thenAnswer(inv -> {
            DdlEvent saved = inv.getArgument(0);
            // save 이후 event 객체에 타임스탬프 반영 (실제 JPA save와 동일한 동작)
            event.setWhitelistAppliedSqlAt(saved.getWhitelistAppliedSqlAt());
            return saved;
        });

        svc.onDdl(DS_ID, event);  // 첫 번째 호출 → 처리됨
        svc.onDdl(DS_ID, event);  // 두 번째 호출 → whitelistAppliedSqlAt != null 이므로 skip

        // DELETE는 정확히 1회
        verify(sqlTableConfigRepository, times(1))
                .deleteBySourceTableAndDatasourceId("orders", DS_ID);
    }

    // ── 시나리오 18: replaySince — 명시적 since 사용 ─────────────────────────

    @Test
    void scenario_replaySince_usesExplicitSince() {
        Instant since = java.time.Instant.now().minusSeconds(900);

        DdlEvent event = DdlEvent.builder().sqlQuery("DROP TABLE orders").build();
        when(ddlEventRepository.findByDatasourceIdAndCreatedAtAfterOrderByCreatedAtAsc(eq(DS_ID), eq(since)))
                .thenReturn(java.util.List.of(event));
        when(sqlTableConfigRepository.deleteBySourceTableAndDatasourceId(any(), any())).thenReturn(1);
        when(ddlEventRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        WhitelistSyncService.ReplayResult result = svc.replaySince(DS_ID, "sql", since);

        assertThat(result.applied()).isEqualTo(1);
        verify(ddlEventRepository).findByDatasourceIdAndCreatedAtAfterOrderByCreatedAtAsc(DS_ID, since);
    }

    @Test
    void scenario_replaySince_nullSince_returnsZero() {
        WhitelistSyncService.ReplayResult result = svc.replaySince(DS_ID, "sql", null);

        assertThat(result.applied()).isZero();
        assertThat(result.skipped()).isZero();
        verifyNoInteractions(ddlEventRepository);
    }

    // ── 시나리오 19: RENAME COLUMN (비파괴적) → AUTO OFF여도 적용 ──────────────

    @Test
    void scenario_renameColumn_ddlEvent_appliedEvenWhenAutoOff() {
        givenSqlAutoOff();
        givenRagAutoOff();

        DdlEvent event = DdlEvent.builder()
                .sqlQuery("ALTER TABLE orders RENAME COLUMN email TO email_address").build();

        SqlTableConfig cfg = new SqlTableConfig();
        cfg.setSourceTable("orders");
        cfg.setDatasourceId(DS_ID);
        cfg.setAllowedColumns(new String[]{"name", "email"});
        cfg.setExcludedColumns(new String[]{});

        when(sqlTableConfigRepository.findBySourceTableAndDatasourceId("orders", DS_ID))
                .thenReturn(java.util.Optional.of(cfg));
        when(sqlTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(ddlEventRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.onDdl(DS_ID, event);

        ArgumentCaptor<SqlTableConfig> cap = ArgumentCaptor.forClass(SqlTableConfig.class);
        verify(sqlTableConfigRepository).save(cap.capture());
        assertThat(cap.getValue().getAllowedColumns())
                .contains("email_address")
                .doesNotContain("email");
    }
}
