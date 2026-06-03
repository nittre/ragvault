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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 사용자 파라미터 프로필 REST API.
 *
 * M5-3: 사용자가 자신의 Stage 4 파라미터 프로필을 조회/수정/초기화.
 * X-User-Email 헤더로 사용자 식별 (TrustedHeaderFilter 가 /api/v1/user/** 에서 통과 허용).
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

    /**
     * GET /api/v1/user/param-profile
     * 프로필 + 시스템 기본값 + 관리자 한계 통합 응답.
     * X-User-Email 헤더로 사용자 식별.
     */
    @GetMapping
    public ParamProfileResponse getProfile(
            @RequestHeader(value = "X-User-Email", required = false) String userEmail) {

        Map<String, Object> params = userParamProfileRepository
                .findByUserEmail(userEmail != null ? userEmail : "")
                .map(UserParamProfile::getParams)
                .orElse(Map.of());

        Map<String, Object> defaults = HardcodedDefaults.get();
        Map<String, ParamLimitInfo> limits = buildLimits();

        return new ParamProfileResponse(params, defaults, limits);
    }

    /**
     * PUT /api/v1/user/param-profile
     * 검증 → 저장 → 캐시 무효화.
     * Guard B 파라미터(sql_temperature, sql_few_shot_examples, max_context_tokens) 변경 시 400.
     */
    @PutMapping
    public ParamProfileResponse updateProfile(
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestBody Map<String, Object> body) {

        if (userEmail == null || userEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-User-Email 헤더가 필요합니다");
        }

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

        return getProfile(userEmail);
    }

    /**
     * DELETE /api/v1/user/param-profile
     * 사용자 파라미터 프로필 초기화 (Stage 4 삭제).
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProfile(
            @RequestHeader(value = "X-User-Email", required = false) String userEmail) {

        if (userEmail == null || userEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-User-Email 헤더가 필요합니다");
        }
        userParamProfileRepository.deleteByUserEmail(userEmail);
        parameterCacheService.evictByUser(userEmail);
    }

    /**
     * DELETE /api/v1/user/param-profile/all
     * 프로필(Stage 4) + 모든 대화별 override(Stage 5) 전체 초기화.
     */
    @DeleteMapping("/all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAll(
            @RequestHeader(value = "X-User-Email", required = false) String userEmail) {

        if (userEmail == null || userEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-User-Email 헤더가 필요합니다");
        }
        userParamProfileRepository.deleteByUserEmail(userEmail);
        conversationParamOverrideRepository.deleteByUserEmail(userEmail);
        parameterCacheService.evictByUser(userEmail);
    }

    /**
     * admin_param_limits 전체를 파라미터별 ParamLimitInfo 맵으로 변환.
     * Guard A(범위 클램핑) / Guard B(강제 고정) 정보를 UI 에 전달.
     */
    private Map<String, ParamLimitInfo> buildLimits() {
        Map<String, ParamLimitInfo> result = new LinkedHashMap<>();
        for (AdminParamLimit limit : adminParamLimitRepository.findAll()) {
            result.put(limit.getParamName(), new ParamLimitInfo(
                    limit.getMinValue(),
                    limit.getMaxValue(),
                    limit.isLocked(),
                    limit.getLockedReason()
            ));
        }
        return result;
    }
}
