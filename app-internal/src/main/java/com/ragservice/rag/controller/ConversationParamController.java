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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 대화별 파라미터 override REST API.
 *
 * M5-3: 사용자가 특정 대화에서만 적용할 Stage 5 파라미터를 관리.
 * ADR-0011: X-User-Email 헤더 → SecurityContext Authentication 으로 사용자 식별.
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

    @PutMapping("/{convId}/param-override")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putOverride(
            @PathVariable String convId,
            Authentication authentication,
            @RequestBody Map<String, Object> body) {

        String userEmail = requireEmail(authentication);

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

    @DeleteMapping("/{convId}/param-override")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOverride(
            @PathVariable String convId,
            Authentication authentication) {

        String userEmail = requireEmail(authentication);
        conversationParamOverrideRepository.deleteByConversationIdAndUserEmail(convId, userEmail);
        parameterCacheService.evictByConversation(userEmail, convId);
    }

    @GetMapping("/{convId}/effective-params")
    public EffectiveParamsResponse getEffectiveParams(
            @PathVariable String convId,
            Authentication authentication) {

        String userEmail = authentication != null ? authentication.getName() : null;
        EffectiveParams effective = parameterResolver.resolve(userEmail, convId, null);
        return new EffectiveParamsResponse(effective.values(), effective.sources());
    }

    private String requireEmail(Authentication authentication) {
        String email = authentication != null ? authentication.getName() : null;
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
        }
        return email;
    }
}
