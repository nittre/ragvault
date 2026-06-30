package com.ragvault.widget.dto;

import com.ragvault.core.domain.SearchConfig;

/**
 * 검색 설정 응답 DTO.
 */
public record SearchConfigDto(
        String key,
        String value,
        String description
) {
    public static SearchConfigDto from(SearchConfig config) {
        return new SearchConfigDto(
                config.getConfigKey(),
                config.getConfigValue(),
                config.getDescription()
        );
    }
}
