package com.ragvault.widget.service;

import com.ragvault.core.domain.SearchConfig;
import com.ragvault.core.repository.SearchConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 검색 설정 서비스.
 *
 * "searchConfig" 캐시 사용. setValue() 시 자동 무효화.
 */
@Service
@RequiredArgsConstructor
public class SearchConfigService {

    private final SearchConfigRepository searchConfigRepository;

    // 기본값 상수
    private static final int DEFAULT_TOP_K = 5;
    private static final double DEFAULT_THRESHOLD = 0.60;
    private static final String DEFAULT_NO_RESULTS =
            "죄송합니다, 해당 내용은 FAQ에서 찾을 수 없습니다. 다른 표현으로 질문하시거나 고객센터에 문의해 주세요.";
    private static final String DEFAULT_INJECTION_BLOCKED =
            "보안 정책에 위반되는 요청입니다. FAQ 관련 질문만 도와드릴 수 있습니다.";

    /**
     * key 에 해당하는 값 반환. 없으면 defaultValue 반환.
     */
    @Cacheable(value = "searchConfig", key = "#key")
    @Transactional(readOnly = true)
    public String getValue(String key, String defaultValue) {
        return searchConfigRepository.findByConfigKey(key)
                .map(SearchConfig::getConfigValue)
                .orElse(defaultValue);
    }

    /**
     * 값 저장 + 캐시 무효화.
     */
    @CacheEvict(value = "searchConfig", allEntries = true)
    @Transactional
    public SearchConfig setValue(String key, String value) {
        SearchConfig config = searchConfigRepository.findByConfigKey(key)
                .orElseGet(() -> SearchConfig.builder()
                        .configKey(key)
                        .configValue(value)
                        .build());
        config.setConfigValue(value);
        config.setUpdatedAt(LocalDateTime.now());
        return searchConfigRepository.save(config);
    }

    /**
     * 전체 설정 목록.
     */
    @Transactional(readOnly = true)
    public List<SearchConfig> getAll() {
        return searchConfigRepository.findAll();
    }

    public int getTopK() {
        String val = getValue("top_k", String.valueOf(DEFAULT_TOP_K));
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return DEFAULT_TOP_K;
        }
    }

    public double getThreshold() {
        String val = getValue("threshold", String.valueOf(DEFAULT_THRESHOLD));
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return DEFAULT_THRESHOLD;
        }
    }

    public String getNoResultsResponse() {
        return getValue("no_results_response", DEFAULT_NO_RESULTS);
    }

    public String getInjectionBlockedResponse() {
        return getValue("injection_blocked_response", DEFAULT_INJECTION_BLOCKED);
    }

    /**
     * 위젯 채팅에서 text-to-sql 경로를 허용할지 여부.
     *
     * 기본값 false — 공개 위젯(익명 방문자)에 SQL 직접 조회를 노출하지 않는다.
     * true 로 켜면 WidgetChatController 가 QueryRouterService(RAG/SQL 라우팅)를 사용한다.
     * 본인 인증 흐름이 없는 상태에서는 내부 검증/데모 용도로만 사용할 것.
     */
    public boolean getSqlEnabled() {
        return Boolean.parseBoolean(getValue("sql_enabled", "false"));
    }
}
