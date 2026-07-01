package com.ragservice.rag.controller;

import com.ragvault.core.repository.DocumentChunkRepository;
import com.ragvault.core.security.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Paperclip / Ollama tool use 연동용 경량 검색 API.
 *
 * Paperclip → Ollama → [tool call: GET /api/v1/search?q=...] → RagVault → 벡터 검색 결과
 *
 * 인증: Bearer API Key (api:chat scope, ApiKeyAuthFilter 적용).
 * PII 마스킹: 응답에 ADR-0008 적용.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final OllamaEmbeddingModel embeddingModel;
    private final DocumentChunkRepository chunkRepository;
    private final PiiMasker piiMasker;

    @Value("${rag.search.default-top-k:5}")
    private int defaultTopK;

    @Value("${rag.search.default-threshold:0.55}")
    private double defaultThreshold;

    /**
     * 벡터 유사도 검색.
     *
     * @param q     검색 질의 (필수)
     * @param topK  반환할 최대 청크 수 (기본값: 5, 최대: defaultTopK * 2)
     * @return {"results": [{content, score, source_table, source_id}]}
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String q,
            @RequestParam(name = "top_k", defaultValue = "5") int topK) {

        log.debug("Paperclip search: q='{}', top_k={}", q, topK);

        // 1. 쿼리 임베딩
        float[] embedding = embeddingModel.embed(q);
        String embeddingJson = toJsonArray(embedding);

        // 2. pgvector 코사인 유사도 검색
        int limit = Math.min(topK, defaultTopK * 2);
        List<Object[]> rows = chunkRepository.findSimilarChunks(embeddingJson, defaultThreshold, limit);

        // 3. PII 마스킹 + 응답 변환 (ADR-0008)
        // DocumentChunkRepositoryImpl 반환 순서: [content, source_table, source_id, score]
        List<Map<String, Object>> results = rows.stream().map(row -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("content", piiMasker.mask(row[0] != null ? row[0].toString() : ""));
            r.put("score", row[3]);
            r.put("source_table", row[1]);
            r.put("source_id", row[2]);
            return r;
        }).toList();

        log.debug("Paperclip search result: {} chunks", results.size());
        return ResponseEntity.ok(Map.of("results", results));
    }

    private String toJsonArray(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
