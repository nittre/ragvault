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



import com.ragvault.core.domain.DataSourceConfig;
import com.ragvault.core.domain.DocumentChunk;
import com.ragvault.core.repository.DocumentChunkRepository;
import com.ragvault.widget.repository.DsRagTableRepository;
import com.ragvault.widget.repository.DsSyncJobRepository;
import com.ragvault.widget.domain.DsSyncJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DsSyncService {

    private static final String EMBEDDING_MODEL = "bge-m3";
    private static final int BATCH_LIMIT = 1000;

    private final DataSourceConfigService dataSourceConfigService;
    private final OllamaEmbeddingModel embeddingModel;
    private final DocumentChunkRepository chunkRepository;
    private final DsRagTableRepository dsRagTableRepository;
    private final DsSyncJobRepository syncJobRepository;

    @Async
    public void syncTableAsync(Integer datasourceId, String tableName) {
        // 테이블명 allowlist 패턴 — SQL 인젝션 방어
        if (!tableName.matches("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$")) {
            log.warn("Invalid table name: {}", tableName);
            return;
        }

        DsSyncJob job = DsSyncJob.builder()
                .datasourceId(datasourceId)
                .tableName(tableName)
                .status("running")
                .startedAt(Instant.now())
                .build();
        job = syncJobRepository.save(job);

        try {
            int count = doSync(datasourceId, tableName);
            job.setStatus("done");
            job.setRowCount(count);
            job.setFinishedAt(Instant.now());

            // last_synced_at 업데이트
            dsRagTableRepository.findByDatasourceId(datasourceId).stream()
                    .filter(t -> tableName.equals(t.getTableName()))
                    .forEach(t -> {
                        t.setLastSyncedAt(Instant.now());
                        dsRagTableRepository.save(t);
                    });

        } catch (Exception e) {
            log.error("Sync failed: dsId={}, table={}, error={}", datasourceId, tableName, e.getMessage(), e);
            job.setStatus("failed");
            job.setErrorMsg(e.getMessage());
            job.setFinishedAt(Instant.now());
        }
        syncJobRepository.save(job);
    }

    private int doSync(Integer datasourceId, String tableName) throws Exception {
        DataSourceConfig config = dataSourceConfigService.findById(datasourceId);
        String sourceTable = "ds_" + datasourceId + "_" + tableName;

        // 기존 청크 전체 삭제 (멱등성)
        chunkRepository.deleteBySourceTable(datasourceId, sourceTable);

        int count = 0;
        try (Connection conn = dataSourceConfigService.openConnection(config)) {
            conn.setAutoCommit(false);
            String sql = "SELECT * FROM `" + tableName + "` LIMIT " + BATCH_LIMIT;
            try (PreparedStatement ps = conn.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setFetchSize(Integer.MIN_VALUE);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    List<String> colNames = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) colNames.add(meta.getColumnName(i));

                    while (rs.next()) {
                        String pkVal = rs.getString(1);
                        StringBuilder sb = new StringBuilder();
                        for (int i = 1; i <= colCount; i++) {
                            if (i > 1) sb.append(" | ");
                            sb.append(colNames.get(i - 1)).append(": ").append(rs.getString(i));
                        }
                        String text = sb.toString();
                        String hash = sha256(text);
                        float[] embedding = embeddingModel.embed(text);

                        DocumentChunk dc = DocumentChunk.builder()
                                .datasourceId(datasourceId)
                                .sourceTable(sourceTable)
                                .sourceId(pkVal != null ? pkVal : String.valueOf(count))
                                .sourceType("db")
                                .chunkIndex(0)
                                .content(text)
                                .contentHash(hash)
                                .tokenCount(text.split("\\s+").length)
                                .embeddingModel(EMBEDDING_MODEL)
                                .tokenizerModel(EMBEDDING_MODEL)
                                .metadata("{\"datasource_id\":" + datasourceId + ",\"table\":\"" + tableName + "\"}")
                                .build();

                        chunkRepository.upsertChunk(dc, embedding);
                        count++;
                    }
                }
            }
        }
        log.info("Sync done: dsId={}, table={}, rows={}", datasourceId, tableName, count);
        return count;
    }

    private String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
