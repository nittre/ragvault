package com.ragvault.widget.controller;

import com.ragvault.widget.domain.SiteKey;
import com.ragvault.widget.service.AuditLogService;
import com.ragvault.widget.service.SiteKeyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
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
    private final AuditLogService auditLogService;

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
    @PostMapping
    public ResponseEntity<SiteKey> create(@RequestBody CreateRequest req,
                                          @AuthenticationPrincipal UserDetails actor,
                                          HttpServletRequest httpReq) {
        SiteKey sk = new SiteKey();
        sk.setSiteKey(siteKeyService.generateKey());
        sk.setLabel(req.label());
        if (req.brandColor() != null) sk.setBrandColor(req.brandColor());
        if (req.botName()    != null) sk.setBotName(req.botName());
        if (req.greeting()   != null) sk.setGreeting(req.greeting());
        if (req.logoUrl()    != null) sk.setLogoUrl(req.logoUrl());

        SiteKey saved = siteKeyService.create(sk);

        auditLogService.log(
                actor != null ? actor.getUsername() : "unknown",
                "SITEKEY_CREATE",
                "site_key", saved.getSiteKey(),
                "label=" + saved.getLabel(),
                httpReq.getRemoteAddr()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * PUT /admin/sitekeys/{id}
     * label, active, brandColor, botName, greeting, logoUrl 수정.
     */
    @PutMapping("/{id}")
    public ResponseEntity<SiteKey> update(@PathVariable Long id,
                                          @RequestBody UpdateRequest req,
                                          @AuthenticationPrincipal UserDetails actor,
                                          HttpServletRequest httpReq) {
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

        auditLogService.log(
                actor != null ? actor.getUsername() : "unknown",
                "SITEKEY_UPDATE",
                "site_key", updated.getSiteKey(),
                "label=" + updated.getLabel() + ",active=" + updated.isActive(),
                httpReq.getRemoteAddr()
        );

        return ResponseEntity.ok(updated);
    }

    /**
     * DELETE /admin/sitekeys/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails actor,
                                       HttpServletRequest httpReq) {
        // 감사 로그를 위해 삭제 전 키 값 조회
        String siteKeyValue = siteKeyService.findAll().stream()
                .filter(s -> s.getId().equals(id))
                .findFirst()
                .map(SiteKey::getSiteKey)
                .orElse(String.valueOf(id));

        siteKeyService.deleteById(id);

        auditLogService.log(
                actor != null ? actor.getUsername() : "unknown",
                "SITEKEY_DELETE",
                "site_key", siteKeyValue,
                null,
                httpReq.getRemoteAddr()
        );

        return ResponseEntity.noContent().build();
    }

    /**
     * POST /admin/sitekeys/{id}/deactivate
     * active = false
     */
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<SiteKey> deactivate(@PathVariable Long id,
                                               @AuthenticationPrincipal UserDetails actor,
                                               HttpServletRequest httpReq) {
        return setActive(id, false, actor, httpReq);
    }

    /**
     * POST /admin/sitekeys/{id}/activate
     * active = true
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<SiteKey> activate(@PathVariable Long id,
                                             @AuthenticationPrincipal UserDetails actor,
                                             HttpServletRequest httpReq) {
        return setActive(id, true, actor, httpReq);
    }

    // -----------------------------------------------------------------------
    // 내부 헬퍼
    // -----------------------------------------------------------------------

    private ResponseEntity<SiteKey> setActive(Long id, boolean active,
                                               UserDetails actor,
                                               HttpServletRequest httpReq) {
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

        auditLogService.log(
                actor != null ? actor.getUsername() : "unknown",
                "SITEKEY_UPDATE",
                "site_key", updated.getSiteKey(),
                "active=" + active,
                httpReq.getRemoteAddr()
        );

        return ResponseEntity.ok(updated);
    }
}
