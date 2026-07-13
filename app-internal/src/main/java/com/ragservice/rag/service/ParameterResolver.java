package com.ragservice.rag.service;

import com.ragservice.rag.domain.AdminParamLimit;
import com.ragservice.rag.dto.EffectiveParams;
import com.ragservice.rag.repository.AdminParamLimitRepository;
import com.ragservice.rag.repository.ConversationParamOverrideRepository;
import com.ragservice.rag.repository.UserParamProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ADR-0005 통합 7단계 우선순위 체인 + Guard A/B 구현.
 *
 * 적용 순서 (위→아래, 나중이 이김):
 *   Stage 1: 관리자 DB 기본값       (AdminDefaultsService — admin_param_limits.default_value, 서버 하드코딩 없음)
 *   Stage 2: search_config DB        (SearchConfigMappingService)
 *   Stage 3: 모델 변형               (Phase 1+ no-op placeholder)
 *   Stage 4: 사용자 프로필           (user_param_profiles)
 *   Stage 5: 대화별 override         (conversation_param_overrides)
 *   Stage 6: 요청별 override         (request body rag_params)
 *   Guard A: 범위 클램핑 (soft)      (admin_param_limits guard_type='A')
 *   Guard B: 강제 고정 (hard)        (admin_param_limits guard_type='B')
 *
 * 캐싱: conversationId non-null + userEmail non-null 일 때 Redis 5분 TTL.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParameterResolver {

    /** Stage 6에서 사전 필터링할 Guard B 키. Guard B가 덮어쓰지만 사전 필터링이 더 명확. */
    private static final Set<String> GUARD_B_USER_KEYS = Set.of(
            "sql_temperature",
            "sql_few_shot_examples",
            "max_context_tokens"
    );

    /**
     * Stage 4(사용자 프로필)/Stage 5(대화별 override)에서 필터링할 키.
     * force_path는 REJECT 의도 분류 가드레일을 우회하는 라우팅 힌트라, 요청 단위(Stage 6)로만
     * 일회성 적용을 허용하고 영구 저장(Stage 4/5)으로는 설정할 수 없게 막는다.
     */
    private static final Set<String> REQUEST_ONLY_KEYS = Set.of(
            "force_path"
    );

    private final AdminDefaultsService adminDefaultsService;
    private final SearchConfigMappingService searchConfigMappingService;
    private final UserParamProfileRepository userParamProfileRepository;
    private final ConversationParamOverrideRepository conversationParamOverrideRepository;
    private final AdminParamLimitRepository adminParamLimitRepository;
    private final ParameterCacheService parameterCacheService;

    /**
     * 7단계 우선순위 체인으로 최종 파라미터를 결정한다.
     *
     * @param userEmail        X-User-Email 헤더에서 추출 (null 허용 — guest)
     * @param conversationId   대화 ID (null = 새 대화, 캐시 스킵)
     * @param requestRagParams request body의 rag_params (null 허용)
     * @return 최종 파라미터 + source 메타데이터
     */
    public EffectiveParams resolve(
            String userEmail,
            String conversationId,
            Map<String, Object> requestRagParams) {

        // 캐시 체크 (conversationId null이면 스킵)
        if (isCacheable(userEmail, conversationId)) {
            Optional<EffectiveParams> cached = parameterCacheService.get(userEmail, conversationId);
            if (cached.isPresent()) {
                log.debug("ParameterResolver cache hit: user={}, conv={}", userEmail, conversationId);
                return cached.get();
            }
        }

        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();

        // Stage 1: 하드코딩 기본값
        applyStage1(params, sources);
        // Stage 2: search_config DB
        applyStage2(params, sources);
        // Stage 3: 모델 변형 (Phase 1+ 구현 예정 — 현재 no-op)
        applyStage3(params, sources);
        // Stage 4: 사용자 프로필
        applyStage4(params, sources, userEmail);
        // Stage 5: 대화별 override
        applyStage5(params, sources, userEmail, conversationId);
        // Stage 6: request body rag_params
        applyStage6(params, sources, requestRagParams);
        // Guard A: 범위 클램핑
        applyGuardA(params, sources);
        // Guard B: 강제 고정
        applyGuardB(params, sources);

        EffectiveParams result = EffectiveParams.of(params, sources);
        log.debug("ParameterResolver resolved: user={}, conv={}, params={}",
                userEmail, conversationId, result.values().keySet());

        // 캐시 저장
        if (isCacheable(userEmail, conversationId)) {
            parameterCacheService.put(userEmail, conversationId, result);
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Stage methods
    // -------------------------------------------------------------------------

    private void applyStage1(Map<String, Object> params, Map<String, String> sources) {
        adminDefaultsService.resolveDefaults().forEach((k, v) -> {
            params.put(k, v);
            sources.put(k, "stage1_admin_default");
        });
    }

    private void applyStage2(Map<String, Object> params, Map<String, String> sources) {
        searchConfigMappingService.getParams().forEach((k, v) -> {
            params.put(k, v);
            sources.put(k, "stage2_search_config");
        });
    }

    /**
     * Stage 3: 모델 변형 (precise / balanced / broad).
     * Phase 1+ 구현 예정 — 현재 no-op placeholder.
     * Open WebUI 드롭다운에서 선택된 모델 이름으로 top_k, temperature 등을 조정할 예정.
     */
    private void applyStage3(Map<String, Object> params, Map<String, String> sources) {
        // Phase 1+ 구현 예정
    }

    private void applyStage4(Map<String, Object> params, Map<String, String> sources, String userEmail) {
        if (userEmail == null || userEmail.isBlank()) {
            return;
        }
        userParamProfileRepository.findByUserEmail(userEmail).ifPresent(profile -> {
            if (profile.getParams() != null) {
                profile.getParams().forEach((k, v) -> {
                    if (REQUEST_ONLY_KEYS.contains(k)) {
                        return; // 요청 단위(Stage 6)로만 허용되는 키는 영구 저장분 무시
                    }
                    params.put(k, v);
                    sources.put(k, "stage4_user_profile");
                });
            }
        });
    }

    private void applyStage5(Map<String, Object> params, Map<String, String> sources,
                              String userEmail, String conversationId) {
        if (userEmail == null || userEmail.isBlank()
                || conversationId == null || conversationId.isBlank()) {
            return;
        }
        conversationParamOverrideRepository
                .findByConversationIdAndUserEmail(conversationId, userEmail)
                .ifPresent(override -> {
                    if (override.getParams() != null) {
                        override.getParams().forEach((k, v) -> {
                            if (REQUEST_ONLY_KEYS.contains(k)) {
                                return; // 요청 단위(Stage 6)로만 허용되는 키는 영구 저장분 무시
                            }
                            params.put(k, v);
                            sources.put(k, "stage5_conversation_override");
                        });
                    }
                });
    }

    /**
     * Stage 6: request body rag_params.
     * Guard B 키(sql_temperature, sql_few_shot_examples, max_context_tokens)는 사전 필터링.
     * Guard B가 나중에 덮어쓰더라도, 사용자 의도 자체를 무시하는 것이 더 명확하다.
     */
    private void applyStage6(Map<String, Object> params, Map<String, String> sources,
                              Map<String, Object> requestRagParams) {
        if (requestRagParams == null || requestRagParams.isEmpty()) {
            return;
        }
        requestRagParams.forEach((k, v) -> {
            if (GUARD_B_USER_KEYS.contains(k)) {
                log.debug("Stage 6: Guard B key '{}' filtered from request params", k);
                return; // Guard B 키는 무시
            }
            params.put(k, v);
            sources.put(k, "stage6_request_override");
        });
    }

    // -------------------------------------------------------------------------
    // Guard methods
    // -------------------------------------------------------------------------

    /**
     * Guard A: 범위 클램핑 (soft, silent).
     * guard_type='A' 인 항목에 min/max 클램핑. fixedValue 는 null.
     */
    private void applyGuardA(Map<String, Object> params, Map<String, String> sources) {
        adminParamLimitRepository.findAll().forEach(limit -> {
            if (limit.isLocked()) {
                return; // Guard B는 applyGuardB에서 처리
            }
            String key = limit.getParamName();
            Object currentValue = params.get(key);
            if (!(currentValue instanceof Number num)) {
                return; // 숫자 파라미터만 클램핑
            }
            double val = num.doubleValue();
            boolean clamped = false;

            if (limit.getMinValue() != null && val < limit.getMinValue().doubleValue()) {
                val = limit.getMinValue().doubleValue();
                clamped = true;
            }
            if (limit.getMaxValue() != null && val > limit.getMaxValue().doubleValue()) {
                val = limit.getMaxValue().doubleValue();
                clamped = true;
            }

            if (clamped) {
                // 원래 타입 보존 (Integer 파라미터는 Integer로)
                Object clampedValue = restoreType(currentValue, val);
                params.put(key, clampedValue);
                sources.put(key, sources.getOrDefault(key, "unknown") + "+guard_a_clamp");
                log.debug("Guard A clamped: key={}, original={}, clamped={}", key,
                        ((Number) currentValue).doubleValue(), val);
            }
        });
    }

    /**
     * Guard B: 강제 고정 (hard, override all).
     * guard_type='B' 인 항목의 fixedValue 로 강제 덮어쓰기.
     * Stage 1~6 + Guard A 결과를 모두 무시한다.
     */
    private void applyGuardB(Map<String, Object> params, Map<String, String> sources) {
        adminParamLimitRepository.findAll().forEach(limit -> {
            if (!limit.isLocked() || limit.getFixedValue() == null) {
                return;
            }
            String key = limit.getParamName();
            Object currentValue = params.get(key);
            if (!(currentValue instanceof Number)) {
                // force_path/hybrid_synthesis_style 같은 문자열(enum) 파라미터는 fixed_value(NUMERIC)로
                // 강제할 수 없다 — 관리자가 실수로 guard_type='B'를 켜도 값을 훼손하지 않도록 방어.
                return;
            }
            double fixedDouble = limit.getFixedValue().doubleValue();

            // 파라미터 타입에 따라 Integer/Double 변환
            Object fixedValue = currentValue instanceof Integer
                    ? (int) fixedDouble
                    : fixedDouble;

            params.put(key, fixedValue);
            sources.put(key, "guard_b_locked");
            log.debug("Guard B locked: key={}, fixedValue={}", key, fixedValue);
        });
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private boolean isCacheable(String userEmail, String conversationId) {
        return userEmail != null && !userEmail.isBlank()
                && conversationId != null && !conversationId.isBlank();
    }

    /**
     * 클램핑된 double 값을 원래 파라미터 타입으로 복원.
     * 원래 값이 Integer 이면 int 로, 아니면 double 그대로.
     */
    private Object restoreType(Object original, double clampedDouble) {
        if (original instanceof Integer) {
            return (int) clampedDouble;
        }
        return clampedDouble;
    }
}
