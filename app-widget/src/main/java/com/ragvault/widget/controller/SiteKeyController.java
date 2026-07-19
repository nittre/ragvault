package com.ragvault.widget.controller;

import com.ragvault.core.security.Auditable;
import com.ragvault.widget.domain.SiteKey;
import com.ragvault.widget.service.SiteKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Site-key Admin CRUD API.
 *
 * 모든 엔드포인트는 /admin/** → JWT 인증 필수 (SecurityConfig).
 * create/update/delete 시 AuditLogService 감사 로그 비동기 기록.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/sitekeys")
@RequiredArgsConstructor
public class SiteKeyController {

    private final SiteKeyService siteKeyService;

    // -----------------------------------------------------------------------
    // 요청 바디 레코드
    // -----------------------------------------------------------------------

    record CreateRequest(
            String label,
            String brandColor,
            String botName,
            String greeting,
            String logoUrl
    ) {}

    record UpdateRequest(
            String label,
            Boolean active,
            String brandColor,
            String botName,
            String greeting,
            String logoUrl
    ) {}

    // -----------------------------------------------------------------------
    // 엔드포인트
    // -----------------------------------------------------------------------

    /**
     * GET /admin/sitekeys
     * 전체 site-key 목록 반환 (생성일 역순).
     */
    @GetMapping
    public ResponseEntity<List<SiteKey>> list() {
        return ResponseEntity.ok(siteKeyService.findAll());
    }

    /**
     * POST /admin/sitekeys
     * Site-key 생성. site_key 값은 자동 생성(pk_live_...).
     */
    @Auditable(action = "'SITEKEY_CREATE'", targetType = "'site_key'",
            targetId = "#result.body.siteKey", detail = "'label=' + #result.body.label")
    @PostMapping
    public ResponseEntity<SiteKey> create(@RequestBody CreateRequest req) {
        SiteKey sk = new SiteKey();
        sk.setSiteKey(siteKeyService.generateKey());
        sk.setLabel(req.label());
        if (req.brandColor() != null) sk.setBrandColor(req.brandColor());
        if (req.botName()    != null) sk.setBotName(req.botName());
        if (req.greeting()   != null) sk.setGreeting(req.greeting());
        if (req.logoUrl()    != null) sk.setLogoUrl(req.logoUrl());

        SiteKey saved = siteKeyService.create(sk);

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * PUT /admin/sitekeys/{id}
     * label, active, brandColor, botName, greeting, logoUrl 수정.
     */
    @Auditable(action = "'SITEKEY_UPDATE'", targetType = "'site_key'", targetId = "#result.body.siteKey",
            detail = "'label=' + #result.body.label + ',active=' + #result.body.active")
    @PutMapping("/{id}")
    public ResponseEntity<SiteKey> update(@PathVariable Long id,
                                          @RequestBody UpdateRequest req) {
        // 기존 엔티티를 읽어서 null 필드는 기존 값 유지
        SiteKey existing = siteKeyService.findAll().stream()
                .filter(s -> s.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("SiteKey not found: " + id));

        SiteKey patch = new SiteKey();
        patch.setLabel(req.label() != null ? req.label() : existing.getLabel());
        patch.setActive(req.active() != null ? req.active() : existing.isActive());
        patch.setBrandColor(req.brandColor() != null ? req.brandColor() : existing.getBrandColor());
        patch.setBotName(req.botName() != null ? req.botName() : existing.getBotName());
        patch.setGreeting(req.greeting() != null ? req.greeting() : existing.getGreeting());
        patch.setLogoUrl(req.logoUrl() != null ? req.logoUrl() : existing.getLogoUrl());

        SiteKey updated = siteKeyService.update(id, patch);

        return ResponseEntity.ok(updated);
    }

    /**
     * DELETE /admin/sitekeys/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        // 감사 로그는 SiteKeyService.deleteById 의 @Auditable 가 처리한다.
        siteKeyService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /admin/sitekeys/{id}/deactivate
     * active = false
     */
    @Auditable(action = "'SITEKEY_UPDATE'", targetType = "'site_key'",
            targetId = "#result.body.siteKey", detail = "'active=false'")
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<SiteKey> deactivate(@PathVariable Long id) {
        return setActive(id, false);
    }

    /**
     * POST /admin/sitekeys/{id}/activate
     * active = true
     */
    @Auditable(action = "'SITEKEY_UPDATE'", targetType = "'site_key'",
            targetId = "#result.body.siteKey", detail = "'active=true'")
    @PostMapping("/{id}/activate")
    public ResponseEntity<SiteKey> activate(@PathVariable Long id) {
        return setActive(id, true);
    }

    // -----------------------------------------------------------------------
    // 내부 헬퍼
    // -----------------------------------------------------------------------

    // self-invocation 이므로 @Auditable 을 붙이지 않는다 (프록시를 거치지 않아 동작하지 않음).
    // 감사 로그는 호출하는 public 엔드포인트(activate/deactivate)의 @Auditable 가 처리한다.
    private ResponseEntity<SiteKey> setActive(Long id, boolean active) {
        SiteKey existing = siteKeyService.findAll().stream()
                .filter(s -> s.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("SiteKey not found: " + id));

        SiteKey patch = new SiteKey();
        patch.setLabel(existing.getLabel());
        patch.setActive(active);
        patch.setBrandColor(existing.getBrandColor());
        patch.setBotName(existing.getBotName());
        patch.setGreeting(existing.getGreeting());
        patch.setLogoUrl(existing.getLogoUrl());

        SiteKey updated = siteKeyService.update(id, patch);

        return ResponseEntity.ok(updated);
    }
}
