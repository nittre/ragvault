package com.ragservice.rag.controller;

import com.ragservice.rag.domain.MaskingRule;
import com.ragservice.rag.repository.MaskingRuleRepository;
import com.ragservice.rag.security.PiiMasker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * PII 마스킹 규칙 관리 Admin API.
 *
 * api:admin scope 필요 (SecurityConfig 에서 강제).
 * 잘못된 정규식은 등록/수정 단계에서 거부 (400) — 런타임 마스킹 실패 방지.
 *
 * ADR-0007 / ADR-0008: PII 마스킹.
 * requirements/07-auth-security.md
 */
@RestController
@RequestMapping("/api/v1/admin/masking-rules")
@RequiredArgsConstructor
public class AdminMaskingRuleController {

    private final MaskingRuleRepository repository;
    private final PiiMasker piiMasker;

    @GetMapping
    public List<MaskingRule> list() {
        return repository.findAllByOrderBySortOrderAsc();
    }

    @PostMapping
    public MaskingRule create(@RequestBody MaskingRule rule) {
        validate(rule);
        rule.setId(null);
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        MaskingRule saved = repository.save(rule);
        piiMasker.evict();
        return saved;
    }

    @PatchMapping("/{id}")
    public MaskingRule update(@PathVariable Long id, @RequestBody MaskingRule update) {
        MaskingRule existing = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "마스킹 규칙을 찾을 수 없습니다"));
        existing.setName(update.getName());
        existing.setPattern(update.getPattern());
        existing.setReplacement(update.getReplacement());
        existing.setLevel(update.getLevel());
        existing.setEnabled(update.isEnabled());
        existing.setSortOrder(update.getSortOrder());
        existing.setUpdatedAt(LocalDateTime.now());
        validate(existing);
        MaskingRule saved = repository.save(existing);
        piiMasker.evict();
        return saved;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        repository.deleteById(id);
        piiMasker.evict();
        return ResponseEntity.noContent().build();
    }

    /**
     * 규칙 필드 검증. 정규식 컴파일 실패 시 400.
     */
    private void validate(MaskingRule rule) {
        if (rule.getName() == null || rule.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "규칙 이름은 필수입니다");
        }
        if (rule.getPattern() == null || rule.getPattern().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "정규식 패턴은 필수입니다");
        }
        if (rule.getReplacement() == null || rule.getReplacement().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "치환 토큰은 필수입니다");
        }
        String level = rule.getLevel();
        if (level == null || !(level.equals("standard") || level.equals("aggressive"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "level 은 standard 또는 aggressive 여야 합니다");
        }
        try {
            Pattern.compile(rule.getPattern());
        } catch (PatternSyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "잘못된 정규식입니다: " + e.getDescription());
        }
    }
}
