package com.ragservice.rag.service;

import com.ragservice.rag.domain.AdminParamLimit;
import com.ragservice.rag.repository.AdminParamLimitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ADR-0005 Stage 1 기본값을 admin_param_limits.default_value 에서 읽어온다.
 *
 * 서버 코드에는 파라미터 기본값을 하드코딩하지 않는다 — 13개 파라미터 전부 관리자가
 * DB에 설정한 값(default_value)만 사용한다. row 자체가 없거나 default_value 가
 * 비어 있으면(관리자가 아직 설정 안 함) 이는 운영 설정 누락이므로 조용히 폴백하지 않고
 * 즉시 요청을 실패시킨다(500) — 설정 누락을 숨기지 않기 위함.
 */
@Service
@RequiredArgsConstructor
public class AdminDefaultsService {

    private final AdminParamLimitRepository adminParamLimitRepository;

    /**
     * 13개 파라미터 전부의 기본값을 타입에 맞게 파싱해 반환.
     *
     * @throws ResponseStatusException 500 — 어떤 파라미터든 row/default_value 가 없으면
     */
    public Map<String, Object> resolveDefaults() {
        Map<String, AdminParamLimit> limitsByName = adminParamLimitRepository.findAll().stream()
                .collect(Collectors.toMap(AdminParamLimit::getParamName, Function.identity()));

        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : ParamTypeRegistry.ALL_KEYS) {
            AdminParamLimit limit = limitsByName.get(key);
            if (limit == null || limit.getDefaultValue() == null || limit.getDefaultValue().isBlank()) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "관리자가 파라미터 '" + key + "'의 기본값(default_value)을 설정하지 않았습니다. "
                                + "관리자 화면에서 파라미터 한도를 먼저 설정해주세요.");
            }
            result.put(key, parseValue(key, limit.getDefaultValue()));
        }
        return result;
    }

    private Object parseValue(String key, String raw) {
        String trimmed = raw.trim();
        try {
            if (ParamTypeRegistry.INT_KEYS.contains(key)) {
                return Integer.parseInt(trimmed);
            }
            if (ParamTypeRegistry.DOUBLE_KEYS.contains(key)) {
                return Double.parseDouble(trimmed);
            }
            return trimmed; // STRING_KEYS
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "파라미터 '" + key + "'의 default_value('" + raw + "')가 올바른 숫자 형식이 아닙니다.");
        }
    }
}
