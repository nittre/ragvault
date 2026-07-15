package com.ragservice.rag.service;
import com.ragvault.core.service.*;

import com.ragservice.rag.domain.*;
import com.ragvault.core.domain.*;
import com.ragservice.rag.repository.*;
import com.ragvault.core.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WhitelistSyncService 단위 테스트.
 *
 * setAutoSync / onDdl / replay / getDriftStatus 로직 검증.
 * 외부 의존성(Repository, SchemaInspector, LLM 서비스)은 Mock 사용.
 */
@ExtendWith(MockitoExtension.class)
class WhitelistSyncServiceTest {

    @Mock SyncModeConfigRepository syncModeConfigRepository;
    @Mock SqlTableConfigRepository sqlTableConfigRepository;
    @Mock RagTableConfigRepository ragTableConfigRepository;
    @Mock DdlEventRepository ddlEventRepository;
    @Mock SchemaInspectorService schemaInspector;
    @Mock SensitivityAnalysisService sensitivityAnalysisService;
    @Mock RagColumnSuggestionService ragColumnSuggestionService;
    @Mock RagTableConfigService ragTableConfigService;

    @InjectMocks WhitelistSyncService svc;

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private SyncModeConfig modeOff(Integer dsId, String type) {
        return SyncModeConfig.builder().datasourceId(dsId).tableType(type)
                .autoSyncEnabled(false).disabledAt(Instant.now().minusSeconds(3600)).build();
    }

    private SyncModeConfig modeOn(Integer dsId, String type) {
        return SyncModeConfig.builder().datasourceId(dsId).tableType(type)
                .autoSyncEnabled(true).build();
    }

    private SqlTableConfig sqlCfg(String table, String... allowedCols) {
        SqlTableConfig c = new SqlTableConfig();
        c.setSourceTable(table);
        c.setDatasourceId(1);
        c.setAllowedColumns(allowedCols.length > 0 ? allowedCols : null);
        return c;
    }

    private RagTableConfig ragCfg(String table, String pkCol, String contentCols) {
        RagTableConfig c = new RagTableConfig();
        c.setSourceTable(table);
        c.setDatasourceId(1);
        c.setPkColumn(pkCol);
        c.setContentColumnsJson(contentCols);
        c.setMetadataColumnsJson("");
        return c;
    }

    // ── setAutoSync ───────────────────────────────────────────────────────────

    @Nested
    class SetAutoSync {

        @Test
        void offToOn_clearsDisabledAt() {
            SyncModeConfig existing = modeOff(1, "sql");
            when(syncModeConfigRepository.findByDatasourceIdAndTableType(1, "sql"))
                    .thenReturn(Optional.of(existing));
            when(syncModeConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            SyncModeConfig result = svc.setAutoSync(1, "sql", true);

            assertThat(result.isAutoSyncEnabled()).isTrue();
            assertThat(result.getDisabledAt()).isNull();
        }

        @Test
        void onToOff_setsDisabledAt() {
            SyncModeConfig existing = modeOn(1, "sql");
            when(syncModeConfigRepository.findByDatasourceIdAndTableType(1, "sql"))
                    .thenReturn(Optional.of(existing));
            when(syncModeConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            SyncModeConfig result = svc.setAutoSync(1, "sql", false);

            assertThat(result.isAutoSyncEnabled()).isFalse();
            assertThat(result.getDisabledAt()).isNotNull();
        }

        @Test
        void offToOff_doesNotChangeDisabledAt() {
            Instant original = Instant.now().minusSeconds(7200);
            SyncModeConfig existing = modeOff(1, "sql");
            existing.setDisabledAt(original);
            when(syncModeConfigRepository.findByDatasourceIdAndTableType(1, "sql"))
                    .thenReturn(Optional.of(existing));
            when(syncModeConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            SyncModeConfig result = svc.setAutoSync(1, "sql", false);

            assertThat(result.getDisabledAt()).isEqualTo(original);
        }
    }

    // ── onDdl (SQL AUTO ON) ───────────────────────────────────────────────────

    @Nested
    class OnDdlSqlAuto {

        @BeforeEach
        void autoOn() {
            // UNKNOWN DDL 케이스는 모드 확인 전에 return하므로 lenient 처리
            lenient().when(syncModeConfigRepository.findByDatasourceIdAndTableType(1, "sql"))
                    .thenReturn(Optional.of(modeOn(1, "sql")));
            lenient().when(syncModeConfigRepository.findByDatasourceIdAndTableType(1, "rag"))
                    .thenReturn(Optional.of(modeOff(1, "rag")));
            lenient().when(schemaInspector.getAllTablesWithSchema(1)).thenReturn(List.of());
        }

        @Test
        void dropTable_hardDeletes() {
            svc.onDdl(1, "DROP TABLE orders");
            verify(sqlTableConfigRepository).deleteBySourceTableAndDatasourceId("orders", 1);
        }

        @Test
        void createTable_upsertsPending() {
            when(sqlTableConfigRepository.findBySourceTableAndDatasourceId("orders", 1))
                    .thenReturn(Optional.empty());
            when(sqlTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            svc.onDdl(1, "CREATE TABLE orders");

            ArgumentCaptor<SqlTableConfig> cap = ArgumentCaptor.forClass(SqlTableConfig.class);
            verify(sqlTableConfigRepository).save(cap.capture());
            assertThat(cap.getValue().getLlmStatus()).isEqualTo("pending");
            assertThat(cap.getValue().getSourceTable()).isEqualTo("orders");
        }

        @Test
        void createTable_existingInactive_reactivates() {
            SqlTableConfig existing = sqlCfg("orders");
            existing.setActive(false);
            when(sqlTableConfigRepository.findBySourceTableAndDatasourceId("orders", 1))
                    .thenReturn(Optional.of(existing));
            when(sqlTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            svc.onDdl(1, "CREATE TABLE orders");

            ArgumentCaptor<SqlTableConfig> cap = ArgumentCaptor.forClass(SqlTableConfig.class);
            verify(sqlTableConfigRepository).save(cap.capture());
            assertThat(cap.getValue().isActive()).isTrue();
        }

        @Test
        void alterDropColumn_removesFromAllowedColumns() {
            SqlTableConfig cfg = sqlCfg("orders", "name", "email", "phone");
            when(sqlTableConfigRepository.findBySourceTableAndDatasourceId("orders", 1))
                    .thenReturn(Optional.of(cfg));
            when(sqlTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            svc.onDdl(1, "ALTER TABLE orders DROP COLUMN email");

            ArgumentCaptor<SqlTableConfig> cap = ArgumentCaptor.forClass(SqlTableConfig.class);
            verify(sqlTableConfigRepository).save(cap.capture());
            assertThat(cap.getValue().getAllowedColumns()).containsExactly("name", "phone");
        }

        @Test
        void alterAddColumn_setsLlmPending() {
            SqlTableConfig cfg = sqlCfg("orders");
            when(sqlTableConfigRepository.findBySourceTableAndDatasourceId("orders", 1))
                    .thenReturn(Optional.of(cfg));
            when(sqlTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            svc.onDdl(1, "ALTER TABLE orders ADD COLUMN phone VARCHAR(20)");

            ArgumentCaptor<SqlTableConfig> cap = ArgumentCaptor.forClass(SqlTableConfig.class);
            verify(sqlTableConfigRepository).save(cap.capture());
            assertThat(cap.getValue().getLlmStatus()).isEqualTo("pending");
        }

        @Test
        void unknownDdl_noRepositoryCall() {
            svc.onDdl(1, "SELECT * FROM orders");
            verifyNoInteractions(sqlTableConfigRepository);
        }
    }

    // ── onDdl (AUTO OFF) ──────────────────────────────────────────────────────

    @Nested
    class OnDdlAutoOff {

        @BeforeEach
        void autoOff() {
            when(syncModeConfigRepository.findByDatasourceIdAndTableType(anyInt(), anyString()))
                    .thenReturn(Optional.of(modeOff(1, "sql")));
        }

        @Test
        void dropTable_noChanges() {
            svc.onDdl(1, "DROP TABLE orders");
            verifyNoInteractions(sqlTableConfigRepository, ragTableConfigRepository);
        }

        @Test
        void createTable_noChanges() {
            svc.onDdl(1, "CREATE TABLE orders");
            verifyNoInteractions(sqlTableConfigRepository, ragTableConfigRepository);
        }
    }

    // ── onDdl (RAG AUTO ON) ───────────────────────────────────────────────────

    @Nested
    class OnDdlRagAuto {

        @BeforeEach
        void autoOn() {
            when(syncModeConfigRepository.findByDatasourceIdAndTableType(1, "sql"))
                    .thenReturn(Optional.of(modeOff(1, "sql")));
            when(syncModeConfigRepository.findByDatasourceIdAndTableType(1, "rag"))
                    .thenReturn(Optional.of(modeOn(1, "rag")));
            // CREATE/ADD 케이스에서만 호출됨
            lenient().when(schemaInspector.getAllTablesWithSchema(1)).thenReturn(List.of());
        }

        @Test
        void dropTable_hardDeletes() {
            svc.onDdl(1, "DROP TABLE orders");
            verify(ragTableConfigRepository).deleteBySourceTableAndDatasourceId("orders", 1);
        }

        @Test
        void alterDropColumn_lastContentCol_hardDeletes() {
            RagTableConfig cfg = ragCfg("orders", "id", "body"); // body가 유일한 content col
            when(ragTableConfigRepository.findBySourceTableAndDatasourceId("orders", 1))
                    .thenReturn(Optional.of(cfg));

            svc.onDdl(1, "ALTER TABLE orders DROP COLUMN body");

            verify(ragTableConfigRepository).delete(cfg);
            verify(ragTableConfigRepository, never()).save(any());
        }

        @Test
        void alterDropColumn_pkCol_hardDeletes() {
            RagTableConfig cfg = ragCfg("orders", "id", "title,body");
            when(ragTableConfigRepository.findBySourceTableAndDatasourceId("orders", 1))
                    .thenReturn(Optional.of(cfg));

            svc.onDdl(1, "ALTER TABLE orders DROP COLUMN id");

            verify(ragTableConfigRepository).delete(cfg);
        }

        @Test
        void alterDropColumn_oneOfMany_updatesContentCols() {
            RagTableConfig cfg = ragCfg("orders", "id", "title,body,description");
            when(ragTableConfigRepository.findBySourceTableAndDatasourceId("orders", 1))
                    .thenReturn(Optional.of(cfg));
            when(ragTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            svc.onDdl(1, "ALTER TABLE orders DROP COLUMN body");

            ArgumentCaptor<RagTableConfig> cap = ArgumentCaptor.forClass(RagTableConfig.class);
            verify(ragTableConfigRepository).save(cap.capture());
            assertThat(cap.getValue().getContentColumnsJson()).doesNotContain("body");
            assertThat(cap.getValue().getContentColumnsJson()).contains("title", "description");
        }

        @Test
        void alterAddColumn_textType_addsToContentColumns() {
            RagTableConfig cfg = ragCfg("orders", "id", "body");
            when(ragTableConfigRepository.findBySourceTableAndDatasourceId("orders", 1))
                    .thenReturn(Optional.of(cfg));
            when(ragTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            // isTextTypeColumn이 "text" 타입을 확인할 수 있도록 스키마 mock 제공
            SchemaInspectorService.ColumnDetail summaryCol =
                    new SchemaInspectorService.ColumnDetail("summary", "text", true, "", false);
            SchemaInspectorService.TableInfo tableInfo =
                    new SchemaInspectorService.TableInfo("orders", "", List.of(summaryCol));
            when(schemaInspector.getAllTablesWithSchema(1)).thenReturn(List.of(tableInfo));

            svc.onDdl(1, "ALTER TABLE orders ADD COLUMN summary TEXT");

            ArgumentCaptor<RagTableConfig> cap = ArgumentCaptor.forClass(RagTableConfig.class);
            verify(ragTableConfigRepository).save(cap.capture());
            // TEXT 타입 컬럼은 contentColumns에 추가
            assertThat(cap.getValue().getContentColumnsJson()).contains("summary");
            // 기존 content 컬럼 보존
            assertThat(cap.getValue().getContentColumnsJson()).contains("body");
            // 무한 폴링 방지 — llmStatus를 "pending"으로 바꾸지 않음
            assertThat(cap.getValue().getLlmStatus()).isNotEqualTo("pending");
        }

        @Test
        void alterAddColumn_nonTextType_addsToMetadataColumns() {
            RagTableConfig cfg = ragCfg("orders", "id", "body");
            when(ragTableConfigRepository.findBySourceTableAndDatasourceId("orders", 1))
                    .thenReturn(Optional.of(cfg));
            when(ragTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            // INT 타입 → metadata로 분류
            SchemaInspectorService.ColumnDetail qtyCol =
                    new SchemaInspectorService.ColumnDetail("qty", "int", false, "", false);
            SchemaInspectorService.TableInfo tableInfo =
                    new SchemaInspectorService.TableInfo("orders", "", List.of(qtyCol));
            when(schemaInspector.getAllTablesWithSchema(1)).thenReturn(List.of(tableInfo));

            svc.onDdl(1, "ALTER TABLE orders ADD COLUMN qty INT");

            ArgumentCaptor<RagTableConfig> cap = ArgumentCaptor.forClass(RagTableConfig.class);
            verify(ragTableConfigRepository).save(cap.capture());
            assertThat(cap.getValue().getMetadataColumnsJson()).contains("qty");
            assertThat(cap.getValue().getContentColumnsJson()).doesNotContain("qty");
        }
    }

    // ── replay ────────────────────────────────────────────────────────────────

    @Nested
    class Replay {

        @Test
        void noDisabledAt_returnsZero() {
            SyncModeConfig cfg = modeOff(1, "sql");
            cfg.setDisabledAt(null);
            when(syncModeConfigRepository.findByDatasourceIdAndTableType(1, "sql"))
                    .thenReturn(Optional.of(cfg));

            WhitelistSyncService.ReplayResult result = svc.replay(1, "sql");

            assertThat(result.applied()).isZero();
            assertThat(result.skipped()).isZero();
            verifyNoInteractions(ddlEventRepository);
        }

        @Test
        void replaysUnprocessedEvents() {
            SyncModeConfig cfg = modeOff(1, "sql");
            cfg.setDisabledAt(Instant.now().minusSeconds(3600));
            when(syncModeConfigRepository.findByDatasourceIdAndTableType(1, "sql"))
                    .thenReturn(Optional.of(cfg));

            DdlEvent e1 = DdlEvent.builder().sqlQuery("DROP TABLE orders").build();
            DdlEvent e2 = DdlEvent.builder().sqlQuery("CREATE TABLE products").build();
            when(ddlEventRepository.findByDatasourceIdAndCreatedAtAfterOrderByCreatedAtAsc(eq(1), any()))
                    .thenReturn(List.of(e1, e2));

            when(sqlTableConfigRepository.deleteBySourceTableAndDatasourceId(any(), any())).thenReturn(1);
            when(sqlTableConfigRepository.findBySourceTableAndDatasourceId(any(), any()))
                    .thenReturn(Optional.empty());
            when(sqlTableConfigRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(schemaInspector.getAllTablesWithSchema(1)).thenReturn(List.of());
            when(ddlEventRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            WhitelistSyncService.ReplayResult result = svc.replay(1, "sql");

            assertThat(result.applied()).isEqualTo(2);
            assertThat(result.skipped()).isZero();
        }

        @Test
        void skipsAlreadyProcessedEvents() {
            SyncModeConfig cfg = modeOff(1, "sql");
            cfg.setDisabledAt(Instant.now().minusSeconds(3600));
            when(syncModeConfigRepository.findByDatasourceIdAndTableType(1, "sql"))
                    .thenReturn(Optional.of(cfg));

            DdlEvent already = DdlEvent.builder()
                    .sqlQuery("DROP TABLE orders")
                    .whitelistAppliedSqlAt(Instant.now())
                    .build();
            when(ddlEventRepository.findByDatasourceIdAndCreatedAtAfterOrderByCreatedAtAsc(eq(1), any()))
                    .thenReturn(List.of(already));

            WhitelistSyncService.ReplayResult result = svc.replay(1, "sql");

            assertThat(result.applied()).isZero();
            assertThat(result.skipped()).isEqualTo(1);
            verify(ddlEventRepository, never()).save(any());
        }

        @Test
        void skipsUnknownDdl() {
            SyncModeConfig cfg = modeOff(1, "sql");
            cfg.setDisabledAt(Instant.now().minusSeconds(3600));
            when(syncModeConfigRepository.findByDatasourceIdAndTableType(1, "sql"))
                    .thenReturn(Optional.of(cfg));

            DdlEvent unknown = DdlEvent.builder().sqlQuery("SELECT 1").build();
            when(ddlEventRepository.findByDatasourceIdAndCreatedAtAfterOrderByCreatedAtAsc(eq(1), any()))
                    .thenReturn(List.of(unknown));

            WhitelistSyncService.ReplayResult result = svc.replay(1, "sql");

            assertThat(result.applied()).isZero();
            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    // ── replaySince ───────────────────────────────────────────────────────────

    @Nested
    class ReplaySince {

        @Test
        void nullSince_returnsZeroWithoutDbAccess() {
            WhitelistSyncService.ReplayResult result = svc.replaySince(1, "sql", null);

            assertThat(result.applied()).isZero();
            assertThat(result.skipped()).isZero();
            verifyNoInteractions(ddlEventRepository);
        }

        @Test
        void nullTableName_skippedAndDeleteNotCalled() {
            // "DROP TABLE" (테이블명 없음) → parseDdlAll → DdlAction(DROP_TABLE, null, …)
            // Fix 전: deleteBySourceTableAndDatasourceId(null, dsId) 호출 → 전체 화이트리스트 삭제 위험
            // Fix 후: tableName() == null 필터에 걸려 skipped 처리
            DdlEvent event = DdlEvent.builder().sqlQuery("DROP TABLE").build();
            Instant since = Instant.now().minusSeconds(3600);

            when(ddlEventRepository.findByDatasourceIdAndCreatedAtAfterOrderByCreatedAtAsc(eq(1), any()))
                    .thenReturn(List.of(event));

            WhitelistSyncService.ReplayResult result = svc.replaySince(1, "sql", since);

            assertThat(result.applied()).isZero();
            assertThat(result.skipped()).isEqualTo(1);
            verify(sqlTableConfigRepository, never()).deleteBySourceTableAndDatasourceId(isNull(), any());
        }

        @Test
        void validDdl_appliedAndTimestampSet() {
            DdlEvent event = DdlEvent.builder().sqlQuery("DROP TABLE orders").build();
            Instant since = Instant.now().minusSeconds(3600);

            when(ddlEventRepository.findByDatasourceIdAndCreatedAtAfterOrderByCreatedAtAsc(eq(1), any()))
                    .thenReturn(List.of(event));
            when(sqlTableConfigRepository.deleteBySourceTableAndDatasourceId("orders", 1)).thenReturn(1);
            when(ddlEventRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            WhitelistSyncService.ReplayResult result = svc.replaySince(1, "sql", since);

            assertThat(result.applied()).isEqualTo(1);
            assertThat(result.skipped()).isZero();
        }

        @Test
        void alreadyAppliedEvent_skipped() {
            DdlEvent event = DdlEvent.builder()
                    .sqlQuery("DROP TABLE orders")
                    .whitelistAppliedSqlAt(Instant.now())
                    .build();
            Instant since = Instant.now().minusSeconds(3600);

            when(ddlEventRepository.findByDatasourceIdAndCreatedAtAfterOrderByCreatedAtAsc(eq(1), any()))
                    .thenReturn(List.of(event));

            WhitelistSyncService.ReplayResult result = svc.replaySince(1, "sql", since);

            assertThat(result.applied()).isZero();
            assertThat(result.skipped()).isEqualTo(1);
            verify(sqlTableConfigRepository, never()).deleteBySourceTableAndDatasourceId(any(), any());
        }
    }

    // ── getDriftStatus ────────────────────────────────────────────────────────

    @Nested
    class GetDriftStatus {

        private SchemaInspectorService.TableInfo tableInfo(String name, String... cols) {
            List<SchemaInspectorService.ColumnDetail> details = java.util.Arrays.stream(cols)
                    .map(c -> new SchemaInspectorService.ColumnDetail(c, "varchar", false, "", false))
                    .collect(java.util.stream.Collectors.toList());
            return new SchemaInspectorService.TableInfo(name, "", details);
        }

        @Test
        void sql_tableMissing() {
            when(schemaInspector.getAllTablesWithSchema(1)).thenReturn(List.of()); // 라이브에 없음
            SqlTableConfig cfg = sqlCfg("orders");
            when(sqlTableConfigRepository.findByDatasourceIdOrderByIdAsc(1)).thenReturn(List.of(cfg));

            List<WhitelistSyncService.DriftEntry> result = svc.getDriftStatus(1, "sql");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).status()).isEqualTo("table_missing");
        }

        @Test
        void sql_columnMismatch() {
            when(schemaInspector.getAllTablesWithSchema(1))
                    .thenReturn(List.of(tableInfo("orders", "id", "name"))); // email 없음
            SqlTableConfig cfg = sqlCfg("orders", "name", "email");
            when(sqlTableConfigRepository.findByDatasourceIdOrderByIdAsc(1)).thenReturn(List.of(cfg));

            List<WhitelistSyncService.DriftEntry> result = svc.getDriftStatus(1, "sql");

            assertThat(result.get(0).status()).isEqualTo("column_mismatch");
            assertThat(result.get(0).missingColumns()).containsExactly("email");
        }

        @Test
        void sql_ok() {
            when(schemaInspector.getAllTablesWithSchema(1))
                    .thenReturn(List.of(tableInfo("orders", "id", "name", "email")));
            SqlTableConfig cfg = sqlCfg("orders", "name", "email");
            when(sqlTableConfigRepository.findByDatasourceIdOrderByIdAsc(1)).thenReturn(List.of(cfg));

            List<WhitelistSyncService.DriftEntry> result = svc.getDriftStatus(1, "sql");

            assertThat(result.get(0).status()).isEqualTo("ok");
            assertThat(result.get(0).missingColumns()).isEmpty();
        }

        @Test
        void rag_tableMissing() {
            when(schemaInspector.getAllTablesWithSchema(1)).thenReturn(List.of());
            RagTableConfig cfg = ragCfg("articles", "id", "body");
            when(ragTableConfigRepository.findByDatasourceIdOrderByIdAsc(1)).thenReturn(List.of(cfg));

            List<WhitelistSyncService.DriftEntry> result = svc.getDriftStatus(1, "rag");

            assertThat(result.get(0).status()).isEqualTo("table_missing");
        }

        @Test
        void rag_contentColumnMissing() {
            when(schemaInspector.getAllTablesWithSchema(1))
                    .thenReturn(List.of(tableInfo("articles", "id", "title"))); // body 없음
            RagTableConfig cfg = ragCfg("articles", "id", "title,body");
            when(ragTableConfigRepository.findByDatasourceIdOrderByIdAsc(1)).thenReturn(List.of(cfg));

            List<WhitelistSyncService.DriftEntry> result = svc.getDriftStatus(1, "rag");

            assertThat(result.get(0).status()).isEqualTo("column_mismatch");
            assertThat(result.get(0).missingColumns()).containsExactly("body");
        }

        @Test
        void rag_ok() {
            when(schemaInspector.getAllTablesWithSchema(1))
                    .thenReturn(List.of(tableInfo("articles", "id", "title", "body")));
            RagTableConfig cfg = ragCfg("articles", "id", "title,body");
            when(ragTableConfigRepository.findByDatasourceIdOrderByIdAsc(1)).thenReturn(List.of(cfg));

            List<WhitelistSyncService.DriftEntry> result = svc.getDriftStatus(1, "rag");

            assertThat(result.get(0).status()).isEqualTo("ok");
        }
    }
}
