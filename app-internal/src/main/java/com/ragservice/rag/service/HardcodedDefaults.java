package com.ragservice.rag.service;

import java.util.HashMap;
import java.util.Map;

/**
 * Stage 1 하드코딩 기본값 — 7단계 파라미터 우선순위 체인의 최후 fallback.
 *
 * requirements/09-user-parameter-tuning.md 섹션 2 기준 13개 파라미터.
 * Guard B(locked) 파라미터 3개도 포함 — ParameterResolver 에서 Guard B 가 덮어씀.
 *
 * ADR-0005: Stage 1 → Stage 2 → ... → Guard A → Guard B 순서.
 */
public final class HardcodedDefaults {

    private HardcodedDefaults() {}

    /**
     * 13개 파라미터 기본값 맵. 불변 복사본 반환.
     *
     * 키 목록 (req09 섹션 2):
     * - 검색:       top_k, similarity_threshold
     * - LLM 생성:   temperature, top_p, max_tokens
     * - Text-SQL:   sql_temperature(B), sql_few_shot_examples(B), query_timeout_sec, max_result_rows
     * - 의도/혼합:  force_path, hybrid_synthesis_style
     * - 대화:       max_history_turns, max_context_tokens(B)
     */
    public static Map<String, Object> get() {
        Map<String, Object> m = new HashMap<>();

        // --- 2-1. 검색 파라미터 ---
        m.put("top_k", 5);                        // 범위: 1~20
        m.put("similarity_threshold", 0.65);       // 범위: 0.0~1.0 (0.05 단위)

        // --- 2-2. LLM 생성 파라미터 ---
        m.put("temperature", 0.7);                 // 범위: 0.0~2.0 (0.1 단위)
        m.put("top_p", 0.9);                       // 범위: 0.0~1.0 (0.05 단위)
        m.put("max_tokens", 2000);                 // 범위: 100~4096

        // --- 2-3. Text-to-SQL 파라미터 ---
        m.put("sql_temperature", 0.1);             // Guard B: 고정값 0.1 (수정 불가)
        m.put("sql_few_shot_examples", 5);         // Guard B: 고정값 5 (수정 불가)
        m.put("query_timeout_sec", 10);            // 범위: 5~60
        m.put("max_result_rows", 1000);            // 범위: 10~10000

        // --- 2-4. 의도 분류 / 혼합 파라미터 ---
        m.put("force_path", "AUTO");               // AUTO | FORCE_RAG | FORCE_SQL | FORCE_HYBRID
        m.put("hybrid_synthesis_style", "BALANCED"); // BALANCED | SQL_FIRST | RAG_FIRST

        // --- 2-5. 대화 관련 파라미터 ---
        m.put("max_history_turns", 10);            // 범위: 1~50
        m.put("max_context_tokens", 5000);         // Guard B: 고정값 5000 (수정 불가)

        return Map.copyOf(m);
    }
}
