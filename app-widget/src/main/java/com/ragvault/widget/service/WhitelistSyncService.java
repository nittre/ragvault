package com.ragvault.widget.service;

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



import com.ragvault.core.domain.RagTableConfig;
import com.ragvault.core.domain.SqlTableConfig;
import com.ragvault.core.repository.RagTableConfigRepository;
import com.ragvault.core.repository.SqlTableConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG/SQL 화이트리스트 드리프트 감지 서비스 (축소 이식).
 *
 * 실제 라이브 스키마와 화이트리스트(rag_table_config / sql_table_config)를 비교해
 * 사라진 테이블·컬럼을 보고한다.
 *
 * rag-practice 의 binlog DDL 자동 동기화/replay/sync-mode 기능은 ragvault 범위 밖이라 제거.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhitelistSyncService {

    private final SqlTableConfigRepository sqlTableConfigRepository;
    private final RagTableConfigRepository ragTableConfigRepository;
    private final SchemaInspectorService schemaInspector;

    public List<DriftEntry> getDriftStatus(Integer dsId, String tableType) {
        List<SchemaInspectorService.TableInfo> live = schemaInspector.getAllTablesWithSchema(dsId);
        Set<String> liveTableNames = live.stream()
                .map(SchemaInspectorService.TableInfo::tableName).collect(Collectors.toSet());
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

    public record DriftEntry(String tableName, String status, List<String> missingColumns) {}
}
