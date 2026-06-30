package com.ragservice.rag.controller;

import com.ragvault.core.domain.SearchConfig;
import com.ragvault.core.repository.SearchConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 검색 파라미터 중앙 관리 Admin API.
 * A5 시나리오: search_config 편집
 *
 * 접근 권한: api:admin scope (SecurityConfig 에서 강제)
 */
@RestController
@RequestMapping("/api/v1/admin/datasources/{dsId}/search-config")
@RequiredArgsConstructor
public class AdminSearchConfigController {

    private final SearchConfigRepository repo;

    @GetMapping
    public ResponseEntity<List<SearchConfig>> list(@PathVariable Integer dsId) {
        return ResponseEntity.ok(repo.findAll());
    }

    @PutMapping("/{key}")
    public ResponseEntity<?> update(
            @PathVariable Integer dsId,
            @PathVariable String key,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-Email", defaultValue = "admin") String userEmail) {
        String value = body.get("value");
        if (value == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "value 필수"));
        }
        return repo.findByConfigKey(key).map(c -> {
            c.setConfigValue(value);
            c.setUpdatedBy(userEmail);
            c.setUpdatedAt(LocalDateTime.now());
            return ResponseEntity.ok((Object) repo.save(c));
        }).orElse(ResponseEntity.notFound().build());
    }
}
