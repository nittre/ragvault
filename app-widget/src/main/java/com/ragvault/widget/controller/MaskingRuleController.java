package com.ragvault.widget.controller;

import com.ragvault.core.domain.MaskingRule;
import com.ragvault.widget.service.MaskingRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * PII 마스킹 규칙 Admin API.
 *
 * GET    /admin/masking         → 전체 규칙 목록
 * POST   /admin/masking         → 규칙 생성
 * PUT    /admin/masking/{id}    → 규칙 수정
 * DELETE /admin/masking/{id}    → 규칙 삭제
 * POST   /admin/masking/reload  → 캐시 무효화 + 확인
 *
 * /admin/** 는 SecurityConfig 에서 JWT 인증 필수로 보호됨.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/masking")
@RequiredArgsConstructor
public class MaskingRuleController {

    private final MaskingRuleService maskingRuleService;

    @GetMapping
    public ResponseEntity<List<MaskingRule>> list() {
        return ResponseEntity.ok(maskingRuleService.findAll());
    }

    @PostMapping
    public ResponseEntity<MaskingRule> create(@RequestBody MaskingRule rule) {
        rule.setId(null); // 신규 생성 강제
        MaskingRule saved = maskingRuleService.save(rule);
        log.info("MaskingRule created: id={}, name={}", saved.getId(), saved.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<MaskingRule> update(@PathVariable Long id,
                                               @RequestBody MaskingRule rule) {
        // 존재 여부 확인
        maskingRuleService.findAll().stream()
                .filter(r -> r.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "MaskingRule not found: " + id));
        rule.setId(id);
        MaskingRule saved = maskingRuleService.save(rule);
        log.info("MaskingRule updated: id={}, name={}", saved.getId(), saved.getName());
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        maskingRuleService.deleteById(id);
        log.info("MaskingRule deleted: id={}", id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        maskingRuleService.evictCache();
        List<MaskingRule> active = maskingRuleService.getEnabledRules();
        log.info("MaskingRule cache evicted and reloaded: {} active rules", active.size());
        return ResponseEntity.ok(Map.of(
                "message", "마스킹 규칙 캐시가 초기화되었습니다.",
                "activeRules", active.size()
        ));
    }
}
