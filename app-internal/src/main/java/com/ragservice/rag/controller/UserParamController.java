package com.ragservice.rag.controller;

import com.ragservice.rag.domain.AdminParamLimit;
import com.ragservice.rag.domain.UserParamProfile;
import com.ragservice.rag.dto.ParamLimitInfo;
import com.ragservice.rag.dto.ParamProfileResponse;
import com.ragservice.rag.repository.AdminParamLimitRepository;
import com.ragservice.rag.repository.ConversationParamOverrideRepository;
import com.ragservice.rag.repository.UserParamProfileRepository;
import com.ragservice.rag.service.HardcodedDefaults;
import com.ragservice.rag.service.ParameterCacheService;
import com.ragservice.rag.service.ParameterValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 사용자 파라미터 프로필 REST API.
 *
 * M5-3: 사용자가 자신의 Stage 4 파라미터 프로필을 조회/수정/초기화.
 * ADR-0011: X-User-Email 헤더 → SecurityContext Authentication 으로 사용자 식별.
 *
 * ADR-0005: 7단계 우선순위 체인 Stage 4 관리.
 * requirements/09-user-parameter-tuning.md
 */
@RestController
@RequestMapping("/api/v1/user/param-profile")
@RequiredArgsConstructor
public class UserParamController {

    private final UserParamProfileRepository userParamProfileRepository;
    private final AdminParamLimitRepository adminParamLimitRepository;
    private final ConversationParamOverrideRepository conversationParamOverrideRepository;
    private final ParameterValidator parameterValidator;
    private final ParameterCacheService parameterCacheService;

    @GetMapping
    public ParamProfileResponse getProfile(Authentication authentication) {
        String userEmail = resolveEmail(authentication);

        Map<String, Object> params = userParamProfileRepository
                .findByUserEmail(userEmail != null ? userEmail : "")
                .map(UserParamProfile::getParams)
                .orElse(Map.of());

        Map<String, Object> defaults = HardcodedDefaults.get();
        Map<String, ParamLimitInfo> limits = buildLimits();

        return new ParamProfileResponse(params, defaults, limits);
    }

    @PutMapping
    public ParamProfileResponse updateProfile(
            Authentication authentication,
            @RequestBody Map<String, Object> body) {

        String userEmail = requireEmail(authentication);

        ParameterValidator.ValidationResult validation = parameterValidator.validate(body);
        if (!validation.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, validation.reason());
        }

        UserParamProfile profile = userParamProfileRepository
                .findByUserEmail(userEmail)
                .orElseGet(() -> {
                    UserParamProfile p = new UserParamProfile();
                    p.setUserEmail(userEmail);
                    return p;
                });

        profile.setParams(body);
        userParamProfileRepository.save(profile);
        parameterCacheService.evictByUser(userEmail);

        return getProfile(authentication);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProfile(Authentication authentication) {
        String userEmail = requireEmail(authentication);
        userParamProfileRepository.deleteByUserEmail(userEmail);
        parameterCacheService.evictByUser(userEmail);
    }

    @DeleteMapping("/all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAll(Authentication authentication) {
        String userEmail = requireEmail(authentication);
        userParamProfileRepository.deleteByUserEmail(userEmail);
        conversationParamOverrideRepository.deleteByUserEmail(userEmail);
        parameterCacheService.evictByUser(userEmail);
    }

    private String resolveEmail(Authentication authentication) {
        return authentication != null ? authentication.getName() : null;
    }

    private String requireEmail(Authentication authentication) {
        String email = resolveEmail(authentication);
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
        }
        return email;
    }

    private Map<String, ParamLimitInfo> buildLimits() {
        Map<String, ParamLimitInfo> result = new LinkedHashMap<>();
        for (AdminParamLimit limit : adminParamLimitRepository.findAll()) {
            result.put(limit.getParamName(), new ParamLimitInfo(
                    limit.getMinValue(),
                    limit.getMaxValue(),
                    limit.isLocked(),
                    limit.getLockedReason(),
                    limit.getFixedValue()
            ));
        }
        return result;
    }
}
