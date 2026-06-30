package com.ragvault.widget.service;

import com.ragvault.core.domain.DocumentChunk;
import com.ragvault.core.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * FAQ 마크다운 파일 청킹 + 임베딩 + pgvector UPSERT 서비스.
 *
 * FaqLoaderRunner 또는 POST /admin/faq/reload 에서 호출.
 * rag-practice ChunkingService 에서 MySQL 의존성 제거 후 단순화.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqChunkingService {

    private final OllamaEmbeddingModel embeddingModel;
    private final DocumentChunkRepository chunkRepository;

    private static final String EMBEDDING_MODEL = "bge-m3";
    private static final String SOURCE_TABLE = "faq_markdown";

    /**
     * 마크다운 텍스트 → 청킹 → 임베딩 → UPSERT.
     *
     * @param fileId   파일 식별자 (파일명 등)
     * @param markdown 마크다운 원문
     * @param chunkSize 청크당 최대 문자 수 (기본 1200)
     * @param overlap   오버랩 문자 수 (기본 200)
     */
    public void ingest(String fileId, String markdown, int chunkSize, int overlap) {
        // 기존 청크 삭제 (re-ingest 멱등성)
        chunkRepository.deleteBySourceTableAndSourceId(SOURCE_TABLE, fileId);

        List<String> chunks = split(markdown, chunkSize, overlap);
        log.info("Ingesting FAQ '{}': {} chunks", fileId, chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String hash = sha256(chunk);
            float[] embedding = embeddingModel.embed(chunk);

            DocumentChunk dc = DocumentChunk.builder()
                    .sourceTable(SOURCE_TABLE)
                    .sourceId(fileId)
                    .sourceType("faq")
                    .chunkIndex(i)
                    .content(chunk)
                    .contentHash(hash)
                    .tokenCount(estimateTokens(chunk))
                    .embeddingModel(EMBEDDING_MODEL)
                    .tokenizerModel(EMBEDDING_MODEL)
                    .metadata(null)
                    .build();

            chunkRepository.upsertChunk(dc, embedding);
        }
        log.info("FAQ '{}' ingested successfully ({} chunks)", fileId, chunks.size());
    }

    /**
     * 기본 파라미터로 ingest.
     */
    public void ingest(String fileId, String markdown) {
        ingest(fileId, markdown, 1200, 200);
    }

    // -------------------------------------------------------------------------
    // 내부 헬퍼
    // -------------------------------------------------------------------------

    /**
     * 마크다운 텍스트 청킹 — 헤더 경계 우선 분할.
     *
     * 구분자 우선순위: "\n## " > "\n### " > "\n\n" > "\n" > ""
     */
    private List<String> split(String text, int chunkChars, int overlapChars) {
        if (text == null || text.isBlank()) return List.of();
        if (text.length() <= chunkChars) return List.of(text.trim());

        String[] separators = {"\n## ", "\n### ", "\n\n", "\n"};
        List<String> result = new ArrayList<>();
        splitRecursive(text, separators, 0, chunkChars, overlapChars, result);
        return result.isEmpty() ? List.of(text.trim()) : result;
    }

    private void splitRecursive(String text, String[] separators, int sepIdx,
                                 int chunkChars, int overlapChars, List<String> result) {
        if (text.length() <= chunkChars) {
            String trimmed = text.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
            return;
        }
        if (sepIdx >= separators.length) {
            int pos = 0;
            while (pos < text.length()) {
                int end = Math.min(pos + chunkChars, text.length());
                result.add(text.substring(pos, end).trim());
                pos += Math.max(1, chunkChars - overlapChars);
            }
            return;
        }

        String sep = separators[sepIdx];
        String[] parts = text.split(java.util.regex.Pattern.quote(sep), -1);
        if (parts.length == 1) {
            splitRecursive(text, separators, sepIdx + 1, chunkChars, overlapChars, result);
            return;
        }

        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String candidate = current.isEmpty() ? part : current + sep + part;
            if (candidate.length() <= chunkChars) {
                current = new StringBuilder(candidate);
            } else {
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
                if (!result.isEmpty() && overlapChars > 0) {
                    String prev = result.get(result.size() - 1);
                    String overlap = prev.length() > overlapChars
                            ? prev.substring(prev.length() - overlapChars) : prev;
                    current = new StringBuilder(overlap + sep + part);
                } else {
                    current = new StringBuilder(part);
                }
            }
        }
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

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.split("\\s+").length;
    }
}
