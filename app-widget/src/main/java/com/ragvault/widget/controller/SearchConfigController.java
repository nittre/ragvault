package com.ragvault.widget.controller;

import com.ragvault.widget.dto.SearchConfigDto;
import com.ragvault.widget.service.SearchConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 검색 설정 Admin API.
 *
 * GET  /admin/search         → 전체 설정 목록
 * PUT  /admin/search/{key}   → 특정 설정값 수정
 *
 * /admin/** 는 SecurityConfig 에서 JWT 인증 필수로 보호됨.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/search")
@RequiredArgsConstructor
public class SearchConfigController {

    private final SearchConfigService searchConfigService;

    @GetMapping
    public ResponseEntity<List<SearchConfigDto>> list() {
        List<SearchConfigDto> result = searchConfigService.getAll().stream()
                .map(SearchConfigDto::from)
                .toList();
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{key}")
    public ResponseEntity<SearchConfigDto> update(@PathVariable String key,
                                                   @RequestBody Map<String, String> body) {
        String value = body.get("value");
        if (value == null) {
            return ResponseEntity.badRequest().build();
        }
        var saved = searchConfigService.setValue(key, value);
        log.info("SearchConfig updated: key={}, value={}", key, value);
        return ResponseEntity.ok(SearchConfigDto.from(saved));
    }
}
