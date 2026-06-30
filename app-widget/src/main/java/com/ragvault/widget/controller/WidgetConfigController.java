package com.ragvault.widget.controller;

import com.ragvault.widget.dto.SiteKeyConfigDto;
import com.ragvault.widget.service.SiteKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 위젯 커스터마이징 설정 공개 API.
 *
 * GET /v1/widget/config
 * - X-Site-Key 헤더 필요 (SiteKeyFilter 가 먼저 검증하므로 이 시점에서는 유효한 키)
 * - 해당 site-key 의 브랜드 색상, 봇 이름, 인사말, 로고 URL 반환
 *
 * SecurityConfig: /v1/widget/** → permitAll (기존 설정 범위 내 — 추가 변경 불필요)
 */
@Slf4j
@RestController
@RequestMapping("/v1/widget")
@RequiredArgsConstructor
public class WidgetConfigController {

    private static final String SITE_KEY_HEADER = "X-Site-Key";

    private final SiteKeyService siteKeyService;

    @GetMapping("/config")
    public ResponseEntity<SiteKeyConfigDto> getConfig(
            @RequestHeader(SITE_KEY_HEADER) String siteKey) {

        return siteKeyService.getConfig(siteKey)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
