package com.ragvault.widget.dto;

/**
 * 위젯 커스터마이징 설정 응답 DTO.
 * GET /v1/widget/config 에서 반환.
 */
public record SiteKeyConfigDto(
        String brandColor,
        String botName,
        String greeting,
        String logoUrl
) {
}
