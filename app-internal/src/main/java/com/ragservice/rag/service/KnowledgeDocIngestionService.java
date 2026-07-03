package com.ragservice.rag.service;

import com.ragvault.core.domain.DocumentChunk;
import com.ragvault.core.repository.DocumentChunkRepository;
import com.ragvault.core.service.ImageCaptioningService;
import com.ragvault.core.service.parser.DocumentParserRouter;
import com.ragvault.core.service.parser.ParsedDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 챗 서비스 지식문서 청킹 + 임베딩 + pgvector UPSERT.
 *
 * 위젯의 KnowledgeIngestionService와 동일한 로직,
 * SOURCE_TABLE = "internal_knowledge_doc" 으로 위젯과 분리.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocIngestionService {

    private final OllamaEmbeddingModel embeddingModel;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentParserRouter parserRouter;
    private final ImageCaptioningService captioningService;

    static final String SOURCE_TABLE = "internal_knowledge_doc";
    private static final String EMBEDDING_MODEL = "bge-m3";

    @Value("${rag.knowledge.vision-model:}")
    private String visionModel;

    public void ingestMarkdown(String docId, String markdown) {
        ingestMarkdown(docId, markdown, 1200, 200);
    }

    public void ingestMarkdown(String docId, String markdown, int chunkSize, int overlap) {
        chunkRepository.deleteBySourceTableAndSourceId(SOURCE_TABLE, docId);
        upsertChunks(docId, markdown, "markdown", chunkSize, overlap);
    }

    public void ingestFile(String docId, byte[] bytes, String filename) {
        chunkRepository.deleteBySourceTableAndSourceId(SOURCE_TABLE, docId);
        ParsedDocument parsed;
        try {
            parsed = parserRouter.parse(bytes, filename);
        } catch (Exception e) {
            throw new IllegalStateException("문서 파싱 실패 '" + docId + "': " + e.getMessage(), e);
        }
        String markdown = inlineCaptions(parsed);
        String ext = DocumentParserRouter.extensionOf(filename);
        upsertChunks(docId, markdown, ext, 1200, 200);
    }

    public void deleteDoc(String docId) {
        chunkRepository.deleteBySourceTableAndSourceId(SOURCE_TABLE, docId);
    }

    private String inlineCaptions(ParsedDocument parsed) {
        if (parsed.images().isEmpty()) return parsed.markdown();
        StringBuilder sb = new StringBuilder(parsed.markdown());
        for (ParsedDocument.ExtractedImage img : parsed.images()) {
            String caption = captioningService.caption(img, visionModel);
            if (caption != null && !caption.isBlank()) {
                sb.append("\n\n> **[이미지]** ").append(caption.trim());
            }
        }
        return sb.toString();
    }

    private void upsertChunks(String docId, String markdown, String sourceType,
                               int chunkSize, int overlap) {
        if (markdown == null || markdown.isBlank()) {
            log.warn("문서 '{}' 내용이 비어 있음, 청킹 건너뜀", docId);
            return;
        }
        List<String> chunks = split(markdown, chunkSize, overlap);
        log.info("지식문서 '{}' 인입: {} 청크 (sourceType={})", docId, chunks.size(), sourceType);
        int failedCount = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            float[] embedding;
            try {
                embedding = embeddingModel.embed(chunk);
            } catch (Exception e) {
                // 특정 청크의 임베딩 모델 호출이 실패해도(예: 극히 짧거나 이례적인 입력으로 인한
                // 임베딩 서버 오류) 문서 전체 인입을 중단하지 않고 해당 청크만 건너뛴다.
                log.warn("청크 {}/{} 임베딩 실패, 건너뜀 '{}': {}", i + 1, chunks.size(), docId, e.getMessage());
                failedCount++;
                continue;
            }
            DocumentChunk dc = DocumentChunk.builder()
                    .sourceTable(SOURCE_TABLE)
                    .sourceId(docId)
                    .sourceType(sourceType)
                    .chunkIndex(i)
                    .content(chunk)
                    .contentHash(sha256(chunk))
                    .tokenCount(estimateTokens(chunk))
                    .embeddingModel(EMBEDDING_MODEL)
                    .tokenizerModel(EMBEDDING_MODEL)
                    .metadata(null)
                    .build();
            chunkRepository.upsertChunk(dc, embedding);
        }
        if (!chunks.isEmpty() && failedCount == chunks.size()) {
            throw new IllegalStateException("문서 '" + docId + "'의 모든 청크(" + chunks.size() + "개) 임베딩 실패");
        }
        log.info("지식문서 '{}' 인입 완료 ({}/{} 청크, 실패 {})", docId, chunks.size() - failedCount, chunks.size(), failedCount);
    }

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
            String t = text.trim();
            if (!t.isEmpty()) result.add(t);
            return;
        }
        if (sepIdx >= separators.length) {
            for (int pos = 0; pos < text.length(); pos += Math.max(1, chunkChars - overlapChars)) {
                result.add(text.substring(pos, Math.min(pos + chunkChars, text.length())).trim());
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
                        if (chunk.length() > chunkChars)
                            splitRecursive(chunk, separators, sepIdx + 1, chunkChars, overlapChars, result);
                        else
                            result.add(chunk);
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
                if (chunk.length() > chunkChars)
                    splitRecursive(chunk, separators, sepIdx + 1, chunkChars, overlapChars, result);
                else
                    result.add(chunk);
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
        return text == null || text.isBlank() ? 0 : text.split("\\s+").length;
    }
}
