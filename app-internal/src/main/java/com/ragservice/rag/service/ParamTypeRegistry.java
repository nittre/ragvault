package com.ragservice.rag.service;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ADR-0005 파라미터 13종의 타입 분류 — 어떤 키가 정수/실수/문자열(enum)인지 정의하는
 * 구조적 메타데이터. 실제 기본값/범위는 전부 admin_param_limits DB에서 오며(하드코딩 없음),
 * 이 레지스트리는 "타입이 무엇인지"만 안다 — "값이 무엇인지"는 모른다.
 */
public final class ParamTypeRegistry {

    private ParamTypeRegistry() {}

    public static final Set<String> INT_KEYS = Set.of(
            "top_k",
            "max_tokens",
            "query_timeout_sec",
            "max_result_rows",
            "max_history_turns",
            "sql_few_shot_examples",
            "max_context_tokens"
    );

    public static final Set<String> DOUBLE_KEYS = Set.of(
            "similarity_threshold",
            "temperature",
            "top_p",
            "sql_temperature"
    );

    public static final Set<String> STRING_KEYS = Set.of(
            "force_path",
            "hybrid_synthesis_style"
    );

    public static final Set<String> ALL_KEYS = Stream.of(INT_KEYS, DOUBLE_KEYS, STRING_KEYS)
            .flatMap(Set::stream)
            .collect(Collectors.toUnmodifiableSet());
}
