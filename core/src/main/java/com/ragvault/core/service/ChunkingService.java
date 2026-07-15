package com.ragvault.core.service;

import com.ragvault.core.domain.DocumentChunk;
import com.ragvault.core.domain.RagTableConfig;
import com.ragvault.core.repository.DocumentChunkRepository;
import com.ragvault.core.security.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 텍스트 청킹 + 임베딩 + UPSERT 서비스.
 *
 * 처리 흐름:
 * 1. MySQL 행 → 텍스트 결합 (title + content_columns)
 * 2. PII 마스킹 (rag_table_config.pii_masking_level 기준, ADR-0008)
 * 3. 청킹 (recursive / per-record 전략)
 * 4. 임베딩 (bge-m3 via OllamaEmbeddingModel)
 * 5. document_chunks UPSERT (content_hash 기반 멱등성, ADR-0001)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChunkingService {

    private final PiiMasker piiMasker;
    private final OllamaEmbeddingModel embeddingModel;
    private final DocumentChunkRepository chunkRepository;

    private static final String EMBEDDING_MODEL = "bge-m3";

    /**
     * MySQL 행 → 청크 UPSERT.
     *
     * @param config rag_table_config 설정
     * @param row    MySQL 행 데이터 (컬럼명 → 값)
     */
    @Retryable(
            retryFor = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 5, maxDelay = 30000)
    )
    public void processRow(RagTableConfig config, Map<String, Object> row) {
        String sourceId = String.valueOf(row.get(config.getPkColumn()));
        String rawText = buildText(config, row);
        String maskedText = piiMasker.mask(rawText, config.getPiiMaskingLevel());

        List<String> chunks = splitIntoChunks(maskedText, config);

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String hash = sha256(chunk);
            // mxbai-embed-large: 문서 임베딩은 접두어 없이 그대로 사용
            float[] embedding = embeddingModel.embed(chunk);

            DocumentChunk dc = DocumentChunk.builder()
                    .datasourceId(config.getDatasourceId())
                    .sourceTable(config.getSourceTable())
                    .sourceId(sourceId)
                    .sourceType(config.getSourceType())
                    .chunkIndex(i)
                    .content(chunk)
                    .contentHash(hash)
                    .tokenCount(estimateTokenCount(chunk))
                    .embeddingModel(EMBEDDING_MODEL)
                    .tokenizerModel(EMBEDDING_MODEL)
                    .metadata(buildMetadata(config, row))
                    .build();

            chunkRepository.upsertChunk(dc, embedding);
        }
        log.debug("Processed {} chunks for {}/{}", chunks.size(), config.getSourceTable(), sourceId);
    }

    @Recover
    public void recoverProcessRow(Exception ex, RagTableConfig config, Map<String, Object> row) {
        log.error("Failed to process row after 3 attempts: table={}, pk={}: {}",
                config.getSourceTable(), row.get(config.getPkColumn()), ex.getMessage());
    }

    /**
     * 특정 소스 레코드의 모든 청크 삭제 (DELETE 이벤트).
     */
    public void deleteChunks(Integer datasourceId, String sourceTable, String sourceId) {
        chunkRepository.deleteBySourceTableAndSourceId(datasourceId, sourceTable, sourceId);
    }

    // -------------------------------------------------------------------------
    // 내부 헬퍼
    // -------------------------------------------------------------------------

    /**
     * 텍스트 청킹.
     *
     * chunking_strategy:
     * - per-record: 레코드 전체를 하나의 청크로
     * - recursive (기본): 문자 수 기준으로 재귀 분할
     *   chunk_size * 4문자 ≈ chunk_size 토큰 (4자≈1토큰 근사)
     */
    private List<String> splitIntoChunks(String text, RagTableConfig config) {
        if (text == null || text.isBlank()) return List.of("");

        int chunkChars = config.getChunkSize() * 4;
        int overlapChars = config.getChunkOverlap() * 4;

        if ("per-record".equals(config.getChunkingStrategy()) || text.length() <= chunkChars) {
            return List.of(text);
        }

        // recursive 전략: 문단 → 문장 → 문자 순서로 분할 시도
        return recursiveSplit(text, chunkChars, overlapChars);
    }

    /**
     * 재귀 문자열 분할 구현.
     *
     * 구분자 우선순위: "\n\n" > "\n" > ". " > " " > ""
     */
    private List<String> recursiveSplit(String text, int chunkChars, int overlapChars) {
        List<String> result = new ArrayList<>();
        String[] separators = {"\n\n", "\n", ". ", " "};

        splitRecursive(text, separators, 0, chunkChars, overlapChars, result);

        return result.isEmpty() ? List.of(text) : result;
    }

    private void splitRecursive(String text, String[] separators, int sepIdx,
                                 int chunkChars, int overlapChars, List<String> result) {
        if (text.length() <= chunkChars) {
            result.add(text);
            return;
        }

        if (sepIdx >= separators.length) {
            // 마지막 수단: 강제 분할
            int pos = 0;
            while (pos < text.length()) {
                int end = Math.min(pos + chunkChars, text.length());
                result.add(text.substring(pos, end));
                pos += Math.max(1, chunkChars - overlapChars);
            }
            return;
        }

        String sep = separators[sepIdx];
        String[] parts = text.split(java.util.regex.Pattern.quote(sep), -1);

        if (parts.length == 1) {
            // 이 구분자로 나눌 수 없음 → 다음 구분자 시도
            splitRecursive(text, separators, sepIdx + 1, chunkChars, overlapChars, result);
            return;
        }

        // 파트들을 chunkChars 이내로 묶음
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String candidate = current.isEmpty()
                    ? part
                    : current + sep + part;

            if (candidate.length() <= chunkChars) {
                current = new StringBuilder(candidate);
            } else {
                // current를 저장하고 새 청크 시작
                if (!current.isEmpty()) {
                    String chunk = current.toString().trim();
                    if (!chunk.isEmpty()) {
                        if (chunk.length() > chunkChars) {
                            splitRecursive(chunk, separators, sepIdx + 1, chunkChars, overlapChars, result);
                        } else {
                            result.add(chunk);
                        }
                    }
                }
                // overlap: 이전 청크의 끝 부분을 새 청크 시작에 포함
                if (!result.isEmpty() && overlapChars > 0) {
                    String prev = result.get(result.size() - 1);
                    String overlap = prev.length() > overlapChars
                            ? prev.substring(prev.length() - overlapChars)
                            : prev;
                    current = new StringBuilder(overlap + sep + part);
                } else {
                    current = new StringBuilder(part);
                }
            }
        }
        // 마지막 current 처리
        if (!current.isEmpty()) {
            String chunk = current.toString().trim();
            if (!chunk.isEmpty()) {
                if (chunk.length() > chunkChars) {
                    splitRecursive(chunk, separators, sepIdx + 1, chunkChars, overlapChars, result);
                } else {
                    result.add(chunk);
                }
            }
        }
    }

    /**
     * config에 따라 행 데이터를 텍스트로 결합.
     */
    private String buildText(RagTableConfig config, Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        if (config.getTitleColumn() != null && row.containsKey(config.getTitleColumn())) {
            Object title = row.get(config.getTitleColumn());
            if (title != null) {
                sb.append(title).append("\n\n");
            }
        }
        for (String col : config.getContentColumns()) {
            if (row.containsKey(col) && row.get(col) != null) {
                sb.append(row.get(col)).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * metadata_columns 기반 메타데이터 JSON 문자열 생성.
     */
    private String buildMetadata(RagTableConfig config, Map<String, Object> row) {
        String[] metaCols = config.getMetadataColumns();
        if (metaCols == null || metaCols.length == 0) return null;

        Map<String, Object> meta = new LinkedHashMap<>();
        for (String col : metaCols) {
            if (row.containsKey(col)) meta.put(col, row.get(col));
        }
        if (meta.isEmpty()) return null;

        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(meta);
        } catch (Exception e) {
            log.warn("Failed to serialize metadata: {}", e.getMessage());
            return null;
        }
    }

    /**
     * SHA-256 해시 (content_hash 멱등성 검사용).
     */
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

    /**
     * 간이 토큰 수 추정 (공백 기준, 정확도보다 속도 우선).
     */
    private int estimateTokenCount(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.split("\\s+").length;
    }
}
