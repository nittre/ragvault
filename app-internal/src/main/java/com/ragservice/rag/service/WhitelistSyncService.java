package com.ragservice.rag.service;

import com.ragvault.core.service.SchemaInspectorService;
import com.ragvault.core.service.DataSourceConfigService;
import com.ragvault.core.service.RagTableConfigService;
import com.ragvault.core.service.SensitivityAnalysisService;
import com.ragvault.core.service.RagColumnSuggestionService;
import com.ragvault.core.service.SchemaDescriptionService;
import com.ragvault.core.service.SqlGeneratorService;
import com.ragvault.core.service.DataSourceRouterService;
import com.ragvault.core.service.RoutingEmbeddingService;
import com.ragvault.core.service.QueryIntent;



import com.ragservice.rag.domain.*;
import com.ragservice.rag.repository.*;
import com.ragvault.core.domain.*;
import com.ragvault.core.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DDL 이벤트 기반 자동 화이트리스트 동기화 서비스.
 *
 * AUTO 모드가 활성화된 datasource 에 대해:
 * - DDL 이벤트 파싱 (DROP/CREATE/RENAME/TRUNCATE/ALTER)
 * - sql_table_config / rag_table_config 화이트리스트 자동 갱신
 * - OFF→ON 전환 시 disabled_at 이후 DDL 재처리(replay)
 * - 드리프트 감지 (실제 스키마 vs 화이트리스트 비교)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhitelistSyncService {

    private final SyncModeConfigRepository syncModeConfigRepository;
    private final SqlTableConfigRepository sqlTableConfigRepository;
    private final RagTableConfigRepository ragTableConfigRepository;
    private final DdlEventRepository ddlEventRepository;
    private final SchemaInspectorService schemaInspector;
    private final SensitivityAnalysisService sensitivityAnalysisService;
    private final RagColumnSuggestionService ragColumnSuggestionService;

    // ── DDL 파싱 ──────────────────────────────────────────────────────────────

    public enum DdlType {
        DROP_TABLE, CREATE_TABLE, RENAME_TABLE, TRUNCATE_TABLE,
        ALTER_DROP_COLUMN, ALTER_ADD_COLUMN, ALTER_RENAME_COLUMN, UNKNOWN
    }

    public record DdlAction(DdlType type, String tableName, String targetName, String columnName) {}

    /**
     * DDL SQL을 파싱해 액션 목록으로 변환.
     * 멀티 절 ALTER TABLE, 멀티 쌍 RENAME TABLE을 지원.
     */
    public List<DdlAction> parseDdlAll(String sql) {
        if (sql == null || sql.isBlank()) return List.of(new DdlAction(DdlType.UNKNOWN, null, null, null));
        String norm = sql.trim().replaceAll("\\s+", " ");
        String upper = norm.toUpperCase();
        try {
            if (upper.startsWith("DROP TABLE")) {
                String rest = norm.substring("DROP TABLE".length()).trim();
                if (rest.toUpperCase().startsWith("IF EXISTS")) rest = rest.substring("IF EXISTS".length()).trim();
                return List.of(new DdlAction(DdlType.DROP_TABLE, extractFirstIdentifier(rest), null, null));
            }
            if (upper.startsWith("CREATE TABLE")) {
                String rest = norm.substring("CREATE TABLE".length()).trim();
                if (rest.toUpperCase().startsWith("IF NOT EXISTS")) rest = rest.substring("IF NOT EXISTS".length()).trim();
                return List.of(new DdlAction(DdlType.CREATE_TABLE, extractFirstIdentifier(rest), null, null));
            }
            if (upper.startsWith("RENAME TABLE")) {
                // RENAME TABLE a TO b, c TO d — 여러 쌍 지원
                String rest = norm.substring("RENAME TABLE".length()).trim();
                String[] pairs = rest.split(",\\s*");
                List<DdlAction> actions = new ArrayList<>();
                for (String pair : pairs) {
                    String[] parts = pair.trim().split("(?i)\\s+TO\\s+", 2);
                    if (parts.length == 2) {
                        actions.add(new DdlAction(DdlType.RENAME_TABLE,
                                extractFirstIdentifier(parts[0]), extractFirstIdentifier(parts[1]), null));
                    }
                }
                return actions.isEmpty() ? List.of(new DdlAction(DdlType.UNKNOWN, null, null, null)) : actions;
            }
            if (upper.startsWith("TRUNCATE")) {
                String rest = norm.substring("TRUNCATE".length()).trim();
                if (rest.toUpperCase().startsWith("TABLE")) rest = rest.substring("TABLE".length()).trim();
                return List.of(new DdlAction(DdlType.TRUNCATE_TABLE, extractFirstIdentifier(rest), null, null));
            }
            if (upper.startsWith("ALTER TABLE")) {
                String rest = norm.substring("ALTER TABLE".length()).trim();
                String tbl = extractFirstIdentifier(rest);
                String afterTbl = rest.replaceFirst(
                        "(?i)^[`\"']?[^`\"'\\s]+[`\"']?(\\.[`\"']?[^`\"'\\s]+[`\"']?)?\\s*", "");
                List<String> clauses = splitAlterClauses(afterTbl);
                List<DdlAction> actions = new ArrayList<>();
                for (String clause : clauses) {
                    DdlAction a = parseAlterClause(tbl, clause);
                    if (a.type() != DdlType.UNKNOWN) actions.add(a);
                }
                return actions.isEmpty() ? List.of(new DdlAction(DdlType.UNKNOWN, null, null, null)) : actions;
            }
        } catch (Exception e) {
            log.warn("DDL 파싱 실패 (UNKNOWN fallback): sql='{}', error={}", sql, e.getMessage());
        }
        return List.of(new DdlAction(DdlType.UNKNOWN, null, null, null));
    }

    /**
     * DDL SQL을 파싱해 첫 번째 액션으로 변환. 단일 절 DDL에 사용.
     * 멀티 절이 필요하면 parseDdlAll() 사용.
     */
    public DdlAction parseDdl(String sql) {
        List<DdlAction> actions = parseDdlAll(sql);
        return actions.get(0);
    }

    /** ALTER TABLE 다중 절을 괄호 깊이를 추적해 분리. 예: "DROP COLUMN a, ADD COLUMN b INT" */
    private List<String> splitAlterClauses(String s) {
        List<String> clauses = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                String clause = s.substring(start, i).trim();
                if (!clause.isBlank()) clauses.add(clause);
                start = i + 1;
            }
        }
        String last = s.substring(start).trim();
        if (!last.isBlank()) clauses.add(last);
        return clauses.isEmpty() ? List.of(s.trim()) : clauses;
    }

    /** ALTER TABLE 단일 절을 DdlAction으로 파싱. */
    private DdlAction parseAlterClause(String tbl, String clause) {
        String upper = clause.toUpperCase();
        if (upper.contains("DROP COLUMN")) {
            String col = extractColumnAfterKeyword(clause, "DROP COLUMN");
            return new DdlAction(DdlType.ALTER_DROP_COLUMN, tbl, null, col);
        }
        if (upper.contains("RENAME COLUMN")) {
            String colPart = clause.replaceFirst("(?i).*RENAME COLUMN\\s+", "");
            String[] cp = colPart.split("(?i)\\s+TO\\s+", 2);
            if (cp.length == 2) {
                return new DdlAction(DdlType.ALTER_RENAME_COLUMN, tbl,
                        extractFirstIdentifier(cp[1]), extractFirstIdentifier(cp[0]));
            }
        }
        if (upper.contains("ADD COLUMN") || upper.matches("(?s).*\\bADD\\b.*")) {
            String col = extractColumnAfterKeyword(clause, "ADD COLUMN");
            if (col == null) col = extractColumnAfterKeyword(clause, "ADD");
            return new DdlAction(DdlType.ALTER_ADD_COLUMN, tbl, null, col);
        }
        return new DdlAction(DdlType.UNKNOWN, tbl, null, null);
    }

    /** 첫 번째 식별자 추출 (백틱·따옴표·스키마 prefix 제거) */
    private String extractFirstIdentifier(String s) {
        if (s == null || s.isBlank()) return null;
        String token = s.trim().split("[\\s(,;]")[0];
        token = token.replaceAll("[`\"'\\[\\]]", "");
        // schema.table → table
        int dot = token.lastIndexOf('.');
        return dot >= 0 ? token.substring(dot + 1) : token;
    }

    /** "DROP COLUMN col_name [type...]" 에서 col_name 추출 */
    private String extractColumnAfterKeyword(String s, String keyword) {
        int idx = s.toUpperCase().indexOf(keyword.toUpperCase());
        if (idx < 0) return null;
        String after = s.substring(idx + keyword.length()).trim();
        return extractFirstIdentifier(after);
    }

    // ── 동기화 모드 조회/변경 ──────────────────────────────────────────────────

    public SyncModeConfig getOrDefault(Integer dsId, String tableType) {
        return syncModeConfigRepository.findByDatasourceIdAndTableType(dsId, tableType)
                .orElseGet(() -> SyncModeConfig.builder()
                        .datasourceId(dsId).tableType(tableType)
                        .autoSyncEnabled(false).build());
    }

    /**
     * 토글 변경.
     * OFF→ON 전환 시 disabledAt 초기화.
     * ON→OFF 전환 시 disabledAt = now.
     */
    public SyncModeConfig setAutoSync(Integer dsId, String tableType, boolean enabled) {
        SyncModeConfig cfg = syncModeConfigRepository.findByDatasourceIdAndTableType(dsId, tableType)
                .orElseGet(() -> SyncModeConfig.builder()
                        .datasourceId(dsId).tableType(tableType).build());
        if (enabled && !cfg.isAutoSyncEnabled()) {
            cfg.setDisabledAt(null); // 활성화하면 disabledAt 초기화
        } else if (!enabled && cfg.isAutoSyncEnabled()) {
            cfg.setDisabledAt(Instant.now());
        }
        cfg.setAutoSyncEnabled(enabled);
        return syncModeConfigRepository.save(cfg);
    }

    // ── DDL 이벤트 처리 (BinlogSyncService에서 호출) ──────────────────────────

    /**
     * binlog DDL 이벤트 발생 시 호출 (String 오버로드 — 테스트 및 내부 직접 호출용).
     * whitelist_applied_*_at 기록 없이 화이트리스트만 변경.
     */
    public void onDdl(Integer dsId, String sql) {
        List<DdlAction> actions = parseDdlAll(sql);
        List<DdlAction> valid = actions.stream()
                .filter(a -> a.type() != DdlType.UNKNOWN && a.tableName() != null).toList();
        if (valid.isEmpty()) return;

        boolean sqlAuto = getOrDefault(dsId, "sql").isAutoSyncEnabled();
        boolean ragAuto = getOrDefault(dsId, "rag").isAutoSyncEnabled();

        if (sqlAuto) valid.forEach(a -> applySqlAction(dsId, a));
        if (ragAuto) valid.forEach(a -> applyRagAction(dsId, a));
    }

    /**
     * binlog DDL 이벤트 발생 시 호출 (DdlEvent 오버로드 — BinlogSyncService 에서 사용).
     * AUTO 모드인 경우만 화이트리스트를 실제로 변경.
     * 처리 후 ddl_events 의 whitelist_applied_*_at 타임스탬프를 기록한다.
     */
    public synchronized void onDdl(Integer dsId, DdlEvent ddlEvent) {
        List<DdlAction> actions = parseDdlAll(ddlEvent.getSqlQuery());
        List<DdlAction> valid = actions.stream()
                .filter(a -> a.type() != DdlType.UNKNOWN && a.tableName() != null).toList();
        if (valid.isEmpty()) return;

        boolean sqlAuto = getOrDefault(dsId, "sql").isAutoSyncEnabled();
        boolean ragAuto = getOrDefault(dsId, "rag").isAutoSyncEnabled();
        // RENAME COLUMN은 비파괴적(컬럼명만 변경)이므로 auto-sync 여부와 무관하게 항상 적용
        boolean hasRename = valid.stream().anyMatch(a -> a.type() == DdlType.ALTER_RENAME_COLUMN);

        boolean changed = false;
        if ((sqlAuto || hasRename) && ddlEvent.getWhitelistAppliedSqlAt() == null) {
            valid.forEach(a -> applySqlAction(dsId, a));
            ddlEvent.setWhitelistAppliedSqlAt(Instant.now());
            changed = true;
        }
        if ((ragAuto || hasRename) && ddlEvent.getWhitelistAppliedRagAt() == null) {
            valid.forEach(a -> applyRagAction(dsId, a));
            ddlEvent.setWhitelistAppliedRagAt(Instant.now());
            changed = true;
        }
        if (changed) {
            ddlEventRepository.save(ddlEvent);
        }
    }

    // ── SQL 화이트리스트 액션 ─────────────────────────────────────────────────

    private void applySqlAction(Integer dsId, DdlAction action) {
        String tbl = action.tableName();
        switch (action.type()) {
            case DROP_TABLE, RENAME_TABLE -> {
                int deleted = sqlTableConfigRepository.deleteBySourceTableAndDatasourceId(tbl, dsId);
                log.info("[SQL Auto] {} hard-deleted: table={}, dsId={}, rows={}", action.type(), tbl, dsId, deleted);
                if (action.type() == DdlType.RENAME_TABLE && action.targetName() != null) {
                    autoRegisterSqlTable(dsId, action.targetName());
                }
            }
            case CREATE_TABLE -> autoRegisterSqlTable(dsId, tbl);
            case ALTER_DROP_COLUMN -> {
                if (action.columnName() == null) return;
                sqlTableConfigRepository.findBySourceTableAndDatasourceId(tbl, dsId).ifPresent(cfg -> {
                    cfg.setAllowedColumns(removeColumn(cfg.getAllowedColumns(), action.columnName()));
                    cfg.setExcludedColumns(removeColumn(cfg.getExcludedColumns(), action.columnName()));
                    sqlTableConfigRepository.save(cfg);
                    log.info("[SQL Auto] ALTER_DROP_COLUMN: table={}, col={}", tbl, action.columnName());
                });
            }
            case ALTER_ADD_COLUMN -> {
                if (action.columnName() == null) return;
                String newCol = action.columnName();
                sqlTableConfigRepository.findBySourceTableAndDatasourceId(tbl, dsId).ifPresent(cfg -> {
                    // allowedColumns가 설정돼 있으면 새 컬럼을 직접 추가 (기존 컬럼 보존)
                    if (cfg.getAllowedColumns() != null && cfg.getAllowedColumns().length > 0) {
                        String[] existing = cfg.getAllowedColumns();
                        if (Arrays.stream(existing).noneMatch(newCol::equals)) {
                            List<String> list = new ArrayList<>(Arrays.asList(existing));
                            list.add(newCol);
                            cfg.setAllowedColumns(list.toArray(new String[0]));
                        }
                    }
                    cfg.setLlmStatus("pending");
                    sqlTableConfigRepository.save(cfg);
                    triggerSqlLlmAsync(dsId, tbl);
                    log.info("[SQL Auto] ALTER_ADD_COLUMN: table={}, col={}", tbl, newCol);
                });
            }
            case ALTER_RENAME_COLUMN -> {
                if (action.columnName() == null || action.targetName() == null) return;
                sqlTableConfigRepository.findBySourceTableAndDatasourceId(tbl, dsId).ifPresent(cfg -> {
                    cfg.setAllowedColumns(renameColumn(cfg.getAllowedColumns(), action.columnName(), action.targetName()));
                    cfg.setExcludedColumns(renameColumn(cfg.getExcludedColumns(), action.columnName(), action.targetName()));
                    sqlTableConfigRepository.save(cfg);
                    log.info("[SQL Auto] ALTER_RENAME_COLUMN: table={}, {}→{}", tbl, action.columnName(), action.targetName());
                });
            }
            default -> {}
        }
    }

    private void autoRegisterSqlTable(Integer dsId, String tableName) {
        // upsert: 이미 존재하면 재활성화, 없으면 신규 등록
        Optional<SqlTableConfig> existing = sqlTableConfigRepository.findBySourceTableAndDatasourceId(tableName, dsId);
        SqlTableConfig cfg = existing.orElseGet(SqlTableConfig::new);
        cfg.setSourceTable(tableName);
        cfg.setDisplayName(tableName);
        cfg.setDatasourceId(dsId);
        cfg.setDataSensitivity("internal");
        cfg.setAllowedGroups(new String[]{"all"});
        cfg.setActive(true);
        cfg.setLlmStatus("pending");
        if (cfg.getCreatedAt() == null) cfg.setCreatedAt(LocalDateTime.now());
        cfg.setUpdatedAt(LocalDateTime.now());
        sqlTableConfigRepository.save(cfg);
        schemaInspector.evictSchemaCache(dsId);
        triggerSqlLlmAsync(dsId, tableName);
        log.info("[SQL Auto] CREATE_TABLE registered: table={}, dsId={}", tableName, dsId);
    }

    private void triggerSqlLlmAsync(Integer dsId, String tableName) {
        List<SchemaInspectorService.TableInfo> schemas = schemaInspector.getAllTablesWithSchema(dsId).stream()
                .filter(t -> t.tableName().equals(tableName)).toList();
        sensitivityAnalysisService.analyzeAndUpdateAsync(dsId, List.of(tableName), schemas);
    }

    // ── RAG 화이트리스트 액션 ─────────────────────────────────────────────────

    private void applyRagAction(Integer dsId, DdlAction action) {
        String tbl = action.tableName();
        switch (action.type()) {
            case DROP_TABLE, RENAME_TABLE -> {
                int deleted = ragTableConfigRepository.deleteBySourceTableAndDatasourceId(tbl, dsId);
                log.info("[RAG Auto] {} hard-deleted: table={}, dsId={}, rows={}", action.type(), tbl, dsId, deleted);
                if (action.type() == DdlType.RENAME_TABLE && action.targetName() != null) {
                    autoRegisterRagTable(dsId, action.targetName());
                }
            }
            case CREATE_TABLE -> autoRegisterRagTable(dsId, tbl);
            case ALTER_DROP_COLUMN -> {
                if (action.columnName() == null) return;
                ragTableConfigRepository.findBySourceTableAndDatasourceId(tbl, dsId).ifPresent(cfg -> {
                    String col = action.columnName();
                    // PK 삭제 시 hard delete
                    if (col.equals(cfg.getPkColumn())) {
                        ragTableConfigRepository.delete(cfg);
                        log.info("[RAG Auto] PK column dropped, hard-deleted: table={}", tbl);
                        return;
                    }
                    String[] newContent = removeColumn(cfg.getContentColumns(), col);
                    String[] newMeta = removeColumn(cfg.getMetadataColumns(), col);
                    cfg.setContentColumns(newContent);
                    cfg.setMetadataColumns(newMeta);
                    if (newContent.length == 0) {
                        // content_columns가 비었을 때: 라이브 스키마에서 텍스트 컬럼 재탐지
                        // 테이블이 살아있는 경우 LLM 오버라이트 등으로 손상된 상태를 복구
                        SchemaInspectorService.TableInfo tableInfo = schemaInspector
                                .getAllTablesWithSchema(dsId).stream()
                                .filter(t -> t.tableName().equals(tbl))
                                .findFirst().orElse(null);
                        if (tableInfo == null) {
                            ragTableConfigRepository.delete(cfg);
                            log.info("[RAG Auto] table gone, hard-deleted: table={}", tbl);
                        } else {
                            autoDetectColumnsForRag(cfg, tableInfo);
                            if (cfg.getContentColumns().length == 0) {
                                ragTableConfigRepository.delete(cfg);
                                log.info("[RAG Auto] no text columns remain, hard-deleted: table={}", tbl);
                            } else {
                                ragTableConfigRepository.save(cfg);
                                log.info("[RAG Auto] ALTER_DROP_COLUMN recovered content_columns: table={}, cols={}",
                                        tbl, String.join(",", cfg.getContentColumns()));
                            }
                        }
                    } else {
                        ragTableConfigRepository.save(cfg);
                        log.info("[RAG Auto] ALTER_DROP_COLUMN: table={}, col={}", tbl, col);
                    }
                });
            }
            case ALTER_ADD_COLUMN -> {
                if (action.columnName() == null) return;
                String newCol = action.columnName();
                ragTableConfigRepository.findBySourceTableAndDatasourceId(tbl, dsId).ifPresent(cfg -> {
                    // 스키마에서 컬럼 타입 조회 → 텍스트 타입이면 content, 아니면 metadata에 추가
                    // LLM 재분석(triggerRagLlmAsync)은 content_columns 전체를 덮어쓰므로 호출하지 않음
                    // llmStatus는 변경하지 않음 — pending으로 바꾸면 LLM 완료 콜백 없이 무한 폴링 발생
                    if (isTextTypeColumn(dsId, tbl, newCol)) {
                        String[] existing = cfg.getContentColumns();
                        if (Arrays.stream(existing).noneMatch(newCol::equals)) {
                            List<String> list = new ArrayList<>(Arrays.asList(existing));
                            list.add(newCol);
                            cfg.setContentColumnsJson(String.join(",", list));
                            ragTableConfigRepository.save(cfg);
                            log.info("[RAG Auto] ALTER_ADD_COLUMN content: table={}, col={}", tbl, newCol);
                        }
                    } else {
                        String[] existing = cfg.getMetadataColumns();
                        if (Arrays.stream(existing).noneMatch(newCol::equals)) {
                            List<String> list = new ArrayList<>(Arrays.asList(existing));
                            list.add(newCol);
                            cfg.setMetadataColumnsJson(String.join(",", list));
                            ragTableConfigRepository.save(cfg);
                            log.info("[RAG Auto] ALTER_ADD_COLUMN metadata: table={}, col={}", tbl, newCol);
                        }
                    }
                });
            }
            case ALTER_RENAME_COLUMN -> {
                if (action.columnName() == null || action.targetName() == null) return;
                ragTableConfigRepository.findBySourceTableAndDatasourceId(tbl, dsId).ifPresent(cfg -> {
                    cfg.setContentColumns(renameColumn(cfg.getContentColumns(), action.columnName(), action.targetName()));
                    cfg.setMetadataColumns(renameColumn(cfg.getMetadataColumns(), action.columnName(), action.targetName()));
                    if (action.columnName().equals(cfg.getPkColumn())) {
                        cfg.setPkColumn(action.targetName());
                    }
                    if (action.columnName().equals(cfg.getTitleColumn())) {
                        cfg.setTitleColumn(action.targetName());
                    }
                    ragTableConfigRepository.save(cfg);
                    log.info("[RAG Auto] ALTER_RENAME_COLUMN: table={}, {}→{}", tbl, action.columnName(), action.targetName());
                });
            }
            default -> {}
        }
    }

    private void autoRegisterRagTable(Integer dsId, String tableName) {
        List<SchemaInspectorService.TableInfo> allSchemas = schemaInspector.getAllTablesWithSchema(dsId);
        Map<String, SchemaInspectorService.TableInfo> schemaMap = allSchemas.stream()
                .collect(Collectors.toMap(SchemaInspectorService.TableInfo::tableName, t -> t));

        Optional<RagTableConfig> existing = ragTableConfigRepository.findBySourceTableAndDatasourceId(tableName, dsId);
        RagTableConfig cfg = existing.orElseGet(RagTableConfig::new);
        cfg.setSourceTable(tableName);
        cfg.setSourceType("mysql");
        cfg.setDatasourceId(dsId);
        cfg.setDataSensitivity("internal");
        cfg.setChunkingStrategy("recursive");
        cfg.setChunkSize(500);
        cfg.setChunkOverlap(50);
        cfg.setActive(true);
        cfg.setLlmStatus("pending");
        autoDetectColumnsForRag(cfg, schemaMap.get(tableName));
        ragTableConfigRepository.save(cfg);
        schemaInspector.evictSchemaCache(dsId);
        triggerRagLlmAsync(dsId, tableName);
        log.info("[RAG Auto] CREATE_TABLE registered: table={}, dsId={}", tableName, dsId);
    }

    private void autoDetectColumnsForRag(RagTableConfig cfg, SchemaInspectorService.TableInfo tableInfo) {
        if (tableInfo == null) {
            if (cfg.getPkColumn() == null) cfg.setPkColumn("id");
            if (cfg.getContentColumnsJson() == null) cfg.setContentColumnsJson("");
            if (cfg.getMetadataColumnsJson() == null) cfg.setMetadataColumnsJson("");
            return;
        }
        List<SchemaInspectorService.ColumnDetail> cols = tableInfo.columns();
        Set<String> CONTENT_TYPES = Set.of("varchar", "text", "mediumtext", "longtext", "char", "tinytext");
        String pk = cols.stream().filter(SchemaInspectorService.ColumnDetail::primaryKey)
                .map(SchemaInspectorService.ColumnDetail::name).findFirst().orElse("id");
        cfg.setPkColumn(pk);
        List<String> content = cols.stream().filter(c -> !c.name().equals(pk))
                .filter(c -> CONTENT_TYPES.contains(c.dataType().toLowerCase()))
                .map(SchemaInspectorService.ColumnDetail::name).toList();
        Set<String> contentSet = new HashSet<>(content);
        List<String> meta = cols.stream().filter(c -> !c.name().equals(pk) && !contentSet.contains(c.name()))
                .map(SchemaInspectorService.ColumnDetail::name).toList();
        cfg.setContentColumnsJson(String.join(",", content));
        cfg.setMetadataColumnsJson(String.join(",", meta));
    }

    private void triggerRagLlmAsync(Integer dsId, String tableName) {
        List<SchemaInspectorService.TableInfo> schemas = schemaInspector.getAllTablesWithSchema(dsId).stream()
                .filter(t -> t.tableName().equals(tableName)).toList();
        ragColumnSuggestionService.suggestAndUpdateAsync(dsId, List.of(tableName), schemas);
    }

    // ── Replay: disabled_at 이후 DDL 이벤트 재처리 ──────────────────────────

    /**
     * OFF→ON 전환 시 사용자가 confirm한 경우 호출.
     * disabled_at 이후 DDL 이벤트를 순서대로 재처리 (멱등).
     */
    public ReplayResult replay(Integer dsId, String tableType) {
        SyncModeConfig cfg = getOrDefault(dsId, tableType);
        return replaySince(dsId, tableType, cfg.getDisabledAt());
    }

    /**
     * 명시적 since 시점 이후 DDL 재처리.
     * async binlog 캐치업 완료 후 triggerSyncAndReplayAsync에서 사용.
     */
    public ReplayResult replaySince(Integer dsId, String tableType, Instant since) {
        if (since == null) return new ReplayResult(0, 0);

        List<DdlEvent> events = ddlEventRepository
                .findByDatasourceIdAndCreatedAtAfterOrderByCreatedAtAsc(dsId, since);

        int applied = 0, skipped = 0;
        for (DdlEvent event : events) {
            // 이미 처리된 이벤트는 스킵 (멱등 처리)
            boolean alreadyApplied = "sql".equals(tableType)
                    ? event.getWhitelistAppliedSqlAt() != null
                    : event.getWhitelistAppliedRagAt() != null;
            if (alreadyApplied) { skipped++; continue; }

            try {
                List<DdlAction> actions = parseDdlAll(event.getSqlQuery());
                List<DdlAction> valid = actions.stream()
                        .filter(a -> a.type() != DdlType.UNKNOWN && a.tableName() != null).toList();
                if (valid.isEmpty()) { skipped++; continue; }

                if ("sql".equals(tableType)) {
                    valid.forEach(a -> applySqlAction(dsId, a));
                    event.setWhitelistAppliedSqlAt(Instant.now());
                } else {
                    valid.forEach(a -> applyRagAction(dsId, a));
                    event.setWhitelistAppliedRagAt(Instant.now());
                }
                ddlEventRepository.save(event);
                applied++;
            } catch (Exception e) {
                log.warn("[Replay] DDL 재처리 실패: id={}, sql={}, error={}", event.getId(), event.getSqlQuery(), e.getMessage());
                skipped++;
            }
        }
        log.info("[Replay] dsId={}, type={}, applied={}, skipped={}", dsId, tableType, applied, skipped);
        return new ReplayResult(applied, skipped);
    }

    // ── 드리프트 감지 ─────────────────────────────────────────────────────────

    public List<DriftEntry> getDriftStatus(Integer dsId, String tableType) {
        List<SchemaInspectorService.TableInfo> live = schemaInspector.getAllTablesWithSchema(dsId);
        Set<String> liveTableNames = live.stream().map(SchemaInspectorService.TableInfo::tableName).collect(Collectors.toSet());
        Map<String, Set<String>> liveColumns = live.stream().collect(Collectors.toMap(
                SchemaInspectorService.TableInfo::tableName,
                t -> t.columns().stream().map(SchemaInspectorService.ColumnDetail::name).collect(Collectors.toSet())
        ));

        List<DriftEntry> result = new ArrayList<>();

        if ("sql".equals(tableType)) {
            for (SqlTableConfig cfg : sqlTableConfigRepository.findByDatasourceIdOrderByIdAsc(dsId)) {
                result.add(computeSqlDrift(cfg, liveTableNames, liveColumns));
            }
        } else {
            for (RagTableConfig cfg : ragTableConfigRepository.findByDatasourceIdOrderByIdAsc(dsId)) {
                result.add(computeRagDrift(cfg, liveTableNames, liveColumns));
            }
        }
        return result;
    }

    private DriftEntry computeSqlDrift(SqlTableConfig cfg, Set<String> liveTableNames, Map<String, Set<String>> liveColumns) {
        if (!liveTableNames.contains(cfg.getSourceTable())) {
            return new DriftEntry(cfg.getSourceTable(), "table_missing", List.of());
        }
        Set<String> liveCols = liveColumns.getOrDefault(cfg.getSourceTable(), Set.of());
        List<String> missing = new ArrayList<>();
        if (cfg.getAllowedColumns() != null) {
            for (String c : cfg.getAllowedColumns()) if (!liveCols.contains(c)) missing.add(c);
        }
        return new DriftEntry(cfg.getSourceTable(), missing.isEmpty() ? "ok" : "column_mismatch", missing);
    }

    private DriftEntry computeRagDrift(RagTableConfig cfg, Set<String> liveTableNames, Map<String, Set<String>> liveColumns) {
        if (!liveTableNames.contains(cfg.getSourceTable())) {
            return new DriftEntry(cfg.getSourceTable(), "table_missing", List.of());
        }
        Set<String> liveCols = liveColumns.getOrDefault(cfg.getSourceTable(), Set.of());
        List<String> missing = new ArrayList<>();
        for (String c : cfg.getContentColumns()) if (!liveCols.contains(c)) missing.add(c);
        for (String c : cfg.getMetadataColumns()) if (!liveCols.contains(c) && !missing.contains(c)) missing.add(c);
        return new DriftEntry(cfg.getSourceTable(), missing.isEmpty() ? "ok" : "column_mismatch", missing);
    }

    // ── 유틸리티 ─────────────────────────────────────────────────────────────

    private static final Set<String> TEXT_COLUMN_TYPES =
            Set.of("varchar", "text", "mediumtext", "longtext", "char", "tinytext");

    /** 라이브 스키마에서 해당 컬럼이 텍스트 타입인지 확인. 스키마에 없으면 false 반환. */
    private boolean isTextTypeColumn(Integer dsId, String tableName, String columnName) {
        return schemaInspector.getAllTablesWithSchema(dsId).stream()
                .filter(t -> t.tableName().equalsIgnoreCase(tableName))
                .flatMap(t -> t.columns().stream())
                .filter(c -> c.name().equalsIgnoreCase(columnName))
                .anyMatch(c -> TEXT_COLUMN_TYPES.contains(c.dataType().toLowerCase()));
    }

    private String[] removeColumn(String[] cols, String col) {
        if (cols == null) return new String[0];
        return Arrays.stream(cols).filter(c -> !c.equals(col)).toArray(String[]::new);
    }

    private String[] renameColumn(String[] cols, String oldName, String newName) {
        if (cols == null) return new String[0];
        return Arrays.stream(cols)
                .map(c -> c.equals(oldName) ? newName : c)
                .toArray(String[]::new);
    }

    public record ReplayResult(int applied, int skipped) {}
    public record DriftEntry(String tableName, String status, List<String> missingColumns) {}
}
