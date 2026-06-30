package com.ragservice.rag.service;

import com.ragservice.rag.repository.BusinessKnowledgeRepository;
import com.ragservice.rag.repository.BusinessKnowledgeRepositoryCustom.KnowledgeRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 백과사전(비즈니스 지식) 동적 검색 서비스 (아임웹 query_knowledge_base 패턴).
 *
 * 데이터소스 단위로 지식을 레코드 단위로 관리한다.
 * 각 항목 = title(도메인 설명) + knowledgeRole(rule|measure) + content(본문) + pinned.
 * - rule    : content 는 규칙·정의(자연어).
 * - measure : content 는 도메인 도출 쿼리(SQL/자연어 하이브리드) — few-shot 예시처럼 작동.
 * - pinned  : 매 SQL 생성마다 항상 주입(메모리 캐시), 비-pinned 는 질문 임베딩 동적 검색.
 *
 * 직교 분해: pinned = "언제 주입되는가", knowledgeRole = "어떻게 렌더링되는가".
 *
 * 기존 RAG pgvector 인프라(OllamaEmbeddingModel, EntityManager native query) 재사용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessRuleService {

    private final OllamaEmbeddingModel embeddingModel;
    private final BusinessKnowledgeRepository repository;

    private static final String ROLE_MEASURE = "measure";
    private static final double DYNAMIC_THRESHOLD = 0.5;
    private static final int DYNAMIC_TOP_K = 5;

    /** pinned 지식의 렌더링 문자열 메모리 캐시 (dsId → 포맷된 블록 리스트). */
    private final ConcurrentHashMap<Integer, List<String>> pinnedCache = new ConcurrentHashMap<>();

    /** 어드민 편집/저장용 항목 (컨트롤러 ↔ 서비스 공유). */
    public record KnowledgeEntry(String title, String knowledgeRole, String content, boolean pinned) {}

    /**
     * 데이터소스의 백과사전을 재색인(임베딩) + pinned 캐시 갱신.
     * entries 가 비어 있으면 기존 지식을 모두 삭제한다.
     * 임베딩 소스 = title + content.
     */
    public void reindex(Integer datasourceId, List<KnowledgeEntry> entries) {
        List<KnowledgeRow> rows = new ArrayList<>();
        if (entries != null) {
            for (KnowledgeEntry e : entries) {
                if (e == null || e.content() == null || e.content().isBlank()) continue;
                String content = e.content().strip();
                String title = e.title() == null ? "" : e.title().strip();
                String role = ROLE_MEASURE.equals(e.knowledgeRole()) ? ROLE_MEASURE : "rule";
                String embedSource = (title.isEmpty() ? "" : title + "\n") + content;
                try {
                    float[] emb = embeddingModel.embed(embedSource);
                    rows.add(new KnowledgeRow(title, content, role, e.pinned(), emb));
                } catch (Exception ex) {
                    log.warn("Business knowledge embedding failed (non-fatal): dsId={}, title={}",
                            datasourceId, title, ex);
                }
            }
        }
        repository.replaceForDatasource(datasourceId, rows);

        // pinned 캐시 즉시 갱신 (단일 변경 경로 → stale 위험 차단)
        List<String> pinned = new ArrayList<>();
        for (KnowledgeRow r : rows) {
            if (r.pinned()) pinned.add(format(r.title(), r.content(), r.knowledgeRole()));
        }
        pinnedCache.put(datasourceId, pinned);
        log.debug("Business knowledge reindexed: dsId={}, rows={}, pinned={}",
                datasourceId, rows.size(), pinned.size());
    }

    /**
     * 질문에 관련된 백과사전 지식 문자열 반환 (pinned 전체 + 동적 top-k).
     *
     * @param question     사용자 질문
     * @param datasourceId 지식 범위 한정용
     * @return role별 포맷 블록을 합친 문자열. 없으면 빈 문자열.
     */
    public String collectRelevant(String question, Integer datasourceId) {
        if (datasourceId == null) return "";

        // pinned (항상 주입) — 메모리 캐시, 미스 시 DB lazy-load
        List<String> pinned = pinnedCache.computeIfAbsent(datasourceId, this::loadPinnedFromDb);

        // 동적 지식 (질문 임베딩 검색)
        List<String> dynamic = new ArrayList<>();
        try {
            String json = toJsonArray(embeddingModel.embed(question));
            for (Object[] r : repository.searchDynamic(json, datasourceId, DYNAMIC_THRESHOLD, DYNAMIC_TOP_K)) {
                dynamic.add(format((String) r[0], (String) r[1], (String) r[2]));   // [title, content, role, score]
            }
        } catch (Exception e) {
            log.warn("Business knowledge dynamic search failed (non-fatal)", e);
        }

        // 백과사전 참조 로깅 — 누락 발견 루프의 단서 (포스트 원칙)
        log.debug("[business-rules] stage=knowledge_reference pinned={} retrieved={}", pinned, dynamic);

        List<String> all = new ArrayList<>(pinned);
        all.addAll(dynamic);
        return all.isEmpty() ? "" : String.join("\n\n", all);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /** 캐시 미스(재기동 직후) 시 DB 에서 pinned 지식을 읽어 포맷. */
    private List<String> loadPinnedFromDb(Integer datasourceId) {
        List<String> pinned = new ArrayList<>();
        for (Object[] r : repository.findPinned(datasourceId)) {
            pinned.add(format((String) r[0], (String) r[1], (String) r[2]));   // [title, content, role]
        }
        return pinned;
    }

    /** role별 프롬프트 렌더링. */
    private String format(String title, String content, String role) {
        boolean hasTitle = title != null && !title.isBlank();
        if (ROLE_MEASURE.equals(role)) {
            return "# 측정 정의" + (hasTitle ? ": " + title : "") + "\n" + content;
        }
        return "# 규칙" + (hasTitle ? ": " + title : "") + "\n" + content;
    }

    private String toJsonArray(float[] embedding) {
        if (embedding == null || embedding.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
