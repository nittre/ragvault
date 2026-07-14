package com.ragservice.rag.controller;

import com.ragservice.rag.domain.AdminParamLimit;
import com.ragservice.rag.dto.ParamLimitInfo;
import com.ragservice.rag.dto.ParamProfileResponse;
import com.ragservice.rag.repository.AdminParamLimitRepository;
import com.ragservice.rag.service.AdminDefaultsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 사용자 파라미터 컨텍스트 조회 REST API.
 *
 * 전역 기본값(Stage 1)과 관리자 한도(Guard A/B)를 조회한다. 사용자 설정은 세션(브라우저) 한정으로만
 * 관리되며 서버에 영구 저장하지 않으므로, 이 컨트롤러는 조회 전용이다.
 */
@RestController
@RequestMapping("/api/v1/user/param-profile")
@RequiredArgsConstructor
public class UserParamController {

    private final AdminParamLimitRepository adminParamLimitRepository;
    private final AdminDefaultsService adminDefaultsService;

    @GetMapping
    public ParamProfileResponse getProfile() {
        Map<String, Object> defaults = adminDefaultsService.resolveDefaults();
        Map<String, ParamLimitInfo> limits = buildLimits();
        return new ParamProfileResponse(defaults, limits);
    }

    private Map<String, ParamLimitInfo> buildLimits() {
        Map<String, ParamLimitInfo> result = new LinkedHashMap<>();
        for (AdminParamLimit limit : adminParamLimitRepository.findAll()) {
            result.put(limit.getParamName(), new ParamLimitInfo(
                    limit.getMinValue(),
                    limit.getMaxValue(),
                    limit.isLocked(),
                    limit.getLockedReason(),
                    limit.getFixedValue(),
                    limit.getDefaultValue()
            ));
        }
        return result;
    }
}
