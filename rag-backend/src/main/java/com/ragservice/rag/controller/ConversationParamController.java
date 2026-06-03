package com.ragservice.rag.controller;

import com.ragservice.rag.domain.ConversationParamOverride;
import com.ragservice.rag.dto.EffectiveParams;
import com.ragservice.rag.dto.EffectiveParamsResponse;
import com.ragservice.rag.repository.ConversationParamOverrideRepository;
import com.ragservice.rag.service.ParameterCacheService;
import com.ragservice.rag.service.ParameterResolver;
import com.ragservice.rag.service.ParameterValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 대화별 파라미터 override REST API.
 *
 * M5-3: 사용자가 특정 대화에서만 적용할 Stage 5 파라미터를 관리.
 * X-User-Email 헤더로 사용자 식별 (TrustedHeaderFilter 가 /api/v1/user/** 에서 통과 허용).
 *
 * ADR-0005: 7단계 우선순위 체인 Stage 5 관리.
 * requirements/09-user-parameter-tuning.md
 */
@RestController
@RequestMapping("/api/v1/user/conversations")
@RequiredArgsConstructor
public class ConversationParamController {

    private final ConversationParamOverrideRepository conversationParamOverrideRepository;
    private final ParameterValidator parameterValidator;
    private final ParameterCacheService parameterCacheService;
    private final ParameterResolver parameterResolver;

    /**
     * PUT /api/v1/user/conversations/{convId}/param-override
     * 대화별 파라미터 override 저장 (Stage 5).
     * 검증 → UPSERT → 캐시 무효화.
     */
    @PutMapping("/{convId}/param-override")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putOverride(
            @PathVariable String convId,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestBody Map<String, Object> body) {

        requireUserEmail(userEmail);

        ParameterValidator.ValidationResult validation = parameterValidator.validate(body);
        if (!validation.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, validation.reason());
        }

        ConversationParamOverride override = conversationParamOverrideRepository
                .findByConversationIdAndUserEmail(convId, userEmail)
                .orElseGet(() -> {
                    ConversationParamOverride o = new ConversationParamOverride();
                    o.setConversationId(convId);
                    o.setUserEmail(userEmail);
                    return o;
                });

        override.setParams(body);
        conversationParamOverrideRepository.save(override);
        parameterCacheService.evictByConversation(userEmail, convId);
    }

    /**
     * DELETE /api/v1/user/conversations/{convId}/param-override
     * 대화별 파라미터 override 삭제 (Stage 5 초기화).
     */
    @DeleteMapping("/{convId}/param-override")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOverride(
            @PathVariable String convId,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail) {

        requireUserEmail(userEmail);
        conversationParamOverrideRepository.deleteByConversationIdAndUserEmail(convId, userEmail);
        parameterCacheService.evictByConversation(userEmail, convId);
    }

    /**
     * GET /api/v1/user/conversations/{convId}/effective-params
     * ParameterResolver 7단계 체인 결과 반환.
     * effective 값 + 각 파라미터의 source(stage) 정보를 응답에 포함.
     */
    @GetMapping("/{convId}/effective-params")
    public EffectiveParamsResponse getEffectiveParams(
            @PathVariable String convId,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail) {

        // userEmail null 허용 (guest 모드) — ParameterResolver 가 null-safe 처리
        EffectiveParams effective = parameterResolver.resolve(userEmail, convId, null);
        return new EffectiveParamsResponse(effective.values(), effective.sources());
    }

    private void requireUserEmail(String userEmail) {
        if (userEmail == null || userEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-User-Email 헤더가 필요합니다");
        }
    }
}
