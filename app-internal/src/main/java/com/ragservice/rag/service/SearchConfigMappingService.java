package com.ragservice.rag.service;

import com.ragvault.core.repository.SearchConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Stage 2: search_config 테이블 값을 13개 파라미터 키 우주로 매핑.
 *
 * V6__m6_admin_schema.sql 에 INSERT 된 search_config 키:
 *   default_top_k      → top_k          (Integer)
 *   default_threshold  → similarity_threshold (Double)
 *   default_temperature→ temperature    (Double)
 *   max_tokens         → max_tokens     (Integer)
 *   (hybrid_alpha, reranking_enabled, context_window 은 파라미터 키 우주 외 → 무시)
 *
 * ADR-0005: Stage 2 — search_config 테이블 기본값.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchConfigMappingService {

    private final SearchConfigRepository searchConfigRepository;

    /**
     * search_config 키 → 파라미터 키 매핑 테이블.
     * KEY_MAP에 없는 search_config 키는 무시 (파라미터 키 우주 외).
     */
    private static final Map<String, String> KEY_MAP = Map.of(
            "default_top_k",       "top_k",
            "default_threshold",   "similarity_threshold",
            "default_temperature", "temperature",
            "max_tokens",          "max_tokens"
    );

    /**
     * search_config 테이블에서 Stage 2 파라미터 맵을 반환.
     *
     * - default_ 접두어 제거 (KEY_MAP 통해 처리)
     * - KEY_MAP에 없는 키는 무시
     * - 값이 없는 파라미터는 맵에 포함하지 않음 (Stage 1 기본값 유지)
     *
     * @return 파라미터 키 → 값 맵 (변환된 타입)
     */
    public Map<String, Object> getParams() {
        Map<String, Object> result = new HashMap<>();

        searchConfigRepository.findAll().forEach(config -> {
            String paramKey = KEY_MAP.get(config.getConfigKey());
            if (paramKey == null) {
                // 파라미터 키 우주 외 키 (hybrid_alpha 등) 무시
                return;
            }
            String rawValue = config.getConfigValue();
            if (rawValue == null || rawValue.isBlank()) {
                return;
            }
            Object parsed = parseValue(paramKey, rawValue);
            if (parsed != null) {
                result.put(paramKey, parsed);
            }
        });

        log.debug("Stage 2 search_config params loaded: {}", result.keySet());
        return result;
    }

    /**
     * 파라미터 키에 따라 String 값을 적절한 타입으로 변환.
     * Integer 파라미터: top_k, max_tokens
     * Double 파라미터: similarity_threshold, temperature
     */
    private Object parseValue(String paramKey, String raw) {
        try {
            return switch (paramKey) {
                case "top_k", "max_tokens" -> Integer.parseInt(raw.trim());
                case "similarity_threshold", "temperature" -> Double.parseDouble(raw.trim());
                default -> raw.trim();
            };
        } catch (NumberFormatException e) {
            log.warn("search_config 값 파싱 실패: paramKey={}, raw={}", paramKey, raw);
            return null;
        }
    }
}
