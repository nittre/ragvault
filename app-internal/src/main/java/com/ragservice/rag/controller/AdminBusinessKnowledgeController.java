package com.ragservice.rag.controller;

import com.ragservice.rag.domain.BusinessKnowledge;
import com.ragservice.rag.repository.BusinessKnowledgeRepository;
import com.ragservice.rag.service.BusinessRuleService;
import com.ragservice.rag.service.BusinessRuleService.KnowledgeEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 데이터소스 단위 백과사전(비즈니스 지식) 관리 API.
 * /api/v1/admin/datasources/{dsId}/knowledge
 *
 * 레코드 단위 구조: title(도메인 설명) + knowledgeRole(rule|measure) + content(본문) + pinned.
 * PUT 은 전체 목록을 받아 재색인(임베딩)한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/datasources/{dsId}/knowledge")
@RequiredArgsConstructor
public class AdminBusinessKnowledgeController {

    private final BusinessKnowledgeRepository repository;
    private final BusinessRuleService businessRuleService;

    /** 저장된 지식 레코드 목록 반환. */
    @GetMapping
    public List<KnowledgeItem> get(@PathVariable Integer dsId) {
        return repository.findByDatasourceIdOrderByIdAsc(dsId).stream()
                .map(this::toItem)
                .toList();
    }

    /** 지식 레코드 목록 저장 → 재색인(임베딩). */
    @PutMapping
    public ResponseEntity<Void> put(@PathVariable Integer dsId, @RequestBody KnowledgeUpdateRequest req) {
        List<KnowledgeEntry> entries = req.items() == null ? List.of() : req.items().stream()
                .map(i -> new KnowledgeEntry(i.title(), i.knowledgeRole(), i.content(), i.pinned()))
                .toList();
        businessRuleService.reindex(dsId, entries);
        log.info("Business knowledge updated: dsId={}, items={}", dsId, entries.size());
        return ResponseEntity.ok().build();
    }

    private KnowledgeItem toItem(BusinessKnowledge bk) {
        return new KnowledgeItem(bk.getId(), bk.getTitle(), bk.getKnowledgeRole(),
                bk.getContent(), bk.isPinned());
    }

    record KnowledgeItem(Long id, String title, String knowledgeRole, String content, boolean pinned) {}

    record KnowledgeUpdateRequest(List<KnowledgeItem> items) {}
}
