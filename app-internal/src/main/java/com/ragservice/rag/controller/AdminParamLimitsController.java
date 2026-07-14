package com.ragservice.rag.controller;

import com.ragservice.rag.domain.AdminParamLimit;
import com.ragservice.rag.repository.AdminParamLimitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 파라미터 Guard A/B 한도 관리 Admin API.
 * A6 시나리오: Guard A 클램핑 / Guard B 강제 고정 (ADR-0005)
 *
 * 접근 권한: api:admin scope (SecurityConfig 에서 강제)
 */
@RestController
@RequestMapping("/api/v1/admin/param-limits")
@RequiredArgsConstructor
public class AdminParamLimitsController {

    private final AdminParamLimitRepository repo;

    @GetMapping
    public ResponseEntity<List<AdminParamLimit>> list() {
        return ResponseEntity.ok(repo.findAllByOrderByIdAsc());
    }

    /**
     * PUT /api/v1/admin/param-limits/{paramKey}/lock
     * Guard B 설정 (강제 고정). ADR-0005.
     */
    @PutMapping("/{paramKey}/lock")
    public AdminParamLimit lockParam(
            @PathVariable String paramKey,
            @RequestBody LockRequest lockRequest) {

        AdminParamLimit limit = repo.findByParamName(paramKey)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "파라미터를 찾을 수 없습니다: " + paramKey));

        limit.setGuardType("B");
        limit.setLockedReason(lockRequest.reason());
        if (lockRequest.fixedValue() != null) {
            limit.setFixedValue(lockRequest.fixedValue());
        }
        limit.setUpdatedAt(LocalDateTime.now());
        return repo.save(limit);
    }

    /**
     * DELETE /api/v1/admin/param-limits/{paramKey}/lock
     * Guard A 복원 (잠금 해제). ADR-0005.
     */
    @DeleteMapping("/{paramKey}/lock")
    public AdminParamLimit unlockParam(@PathVariable String paramKey) {
        AdminParamLimit limit = repo.findByParamName(paramKey)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "파라미터를 찾을 수 없습니다: " + paramKey));

        limit.setGuardType("A");
        limit.setLockedReason(null);
        limit.setFixedValue(null);
        limit.setUpdatedAt(LocalDateTime.now());
        return repo.save(limit);
    }

    /** Guard B 잠금 요청 DTO. */
    public record LockRequest(String reason, BigDecimal fixedValue) {}

    @PutMapping("/{paramName}")
    public ResponseEntity<?> update(
            @PathVariable String paramName,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-Email", defaultValue = "admin") String email) {
        return repo.findAll().stream()
                .filter(p -> p.getParamName().equals(paramName))
                .findFirst()
                .map(p -> {
                    if (body.containsKey("minValue")) {
                        p.setMinValue(new BigDecimal(body.get("minValue").toString()));
                    }
                    if (body.containsKey("maxValue")) {
                        p.setMaxValue(new BigDecimal(body.get("maxValue").toString()));
                    }
                    if (p.getMinValue() != null && p.getMaxValue() != null
                            && p.getMinValue().compareTo(p.getMaxValue()) > 0) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "minValue는 maxValue보다 클 수 없습니다.");
                    }
                    if (body.containsKey("fixedValue")) {
                        Object fv = body.get("fixedValue");
                        p.setFixedValue(fv == null ? null : new BigDecimal(fv.toString()));
                        p.setGuardType(fv == null ? "A" : "B");
                    }
                    if (body.containsKey("defaultValue")) {
                        // ADR-0005: Stage 1 기본값 — 서버 코드에는 하드코딩하지 않고 이 필드로만 설정한다.
                        Object dv = body.get("defaultValue");
                        p.setDefaultValue(dv == null ? null : dv.toString());
                    }
                    p.setUpdatedBy(email);
                    p.setUpdatedAt(LocalDateTime.now());
                    AdminParamLimit saved = repo.save(p);
                    return ResponseEntity.ok((Object) saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
