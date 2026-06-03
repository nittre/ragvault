package com.ragservice.rag.controller;

import com.ragservice.rag.service.ResponseRawStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * PII 마스킹 실패 진단용 원본 응답 조회 Admin API.
 *
 * api:incident-response scope 필요 (SecurityConfig 에서 강제).
 * TTL = 30분 — 기간 초과 후 404 반환.
 *
 * ADR-0010: Short-lived Storage 원본 응답 조회
 * requirements/07-auth-security.md
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@RequiredArgsConstructor
public class AdminRawResponseController {

    private final ResponseRawStorageService rawStorage;

    /**
     * responseId 로 원본 LLM 응답 조회.
     *
     * @param responseId URL 경로의 responseId (Redis key 에서 "resp_raw:" prefix 제외한 부분)
     */
    @GetMapping("/{responseId}/raw")
    public ResponseEntity<Map<String, String>> getRaw(@PathVariable String responseId) {
        return rawStorage.retrieve("resp_raw:" + responseId)
                .map(raw -> ResponseEntity.ok(Map.of("raw", raw)))
                .orElse(ResponseEntity.notFound().build());
    }
}
