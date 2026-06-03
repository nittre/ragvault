package com.ragservice.rag.dto;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OpenAI 호환 chat message DTO.
 *
 * content는 두 가지 형태를 지원한다:
 *   - String: 일반 텍스트 메시지
 *   - List<Map>: 멀티모달 메시지 (텍스트 + 이미지)
 *     [{"type":"text","text":"..."}, {"type":"image_url","image_url":{"url":"data:image/...;base64,..."}}]
 *
 * Jackson이 JSON 문자열 → String, JSON 배열 → ArrayList<LinkedHashMap>으로 역직렬화한다.
 */
public record ChatMessage(String role, Object content) {

    /** 텍스트 내용만 추출 (멀티모달이면 text 파트만 이어붙임) */
    public String textContent() {
        if (content == null) return "";
        if (content instanceof String s) return s;
        if (content instanceof List<?> parts) {
            return parts.stream()
                    .filter(p -> p instanceof Map)
                    .map(p -> (Map<?, ?>) p)
                    .filter(p -> "text".equals(p.get("type")))
                    .map(p -> String.valueOf(p.get("text")))
                    .collect(Collectors.joining(" "));
        }
        return content.toString();
    }

    /**
     * 이미지 base64 데이터 추출.
     * Open WebUI는 이미지를 "data:image/jpeg;base64,<data>" 형태의 URL로 전달한다.
     * base64 데이터만 잘라서 반환한다.
     */
    public List<String> extractImages() {
        if (!(content instanceof List<?> parts)) return Collections.emptyList();
        return parts.stream()
                .filter(p -> p instanceof Map)
                .map(p -> (Map<?, ?>) p)
                .filter(p -> "image_url".equals(p.get("type")))
                .map(p -> {
                    Object imgObj = p.get("image_url");
                    if (imgObj instanceof Map<?, ?> imgMap) {
                        String url = String.valueOf(imgMap.get("url"));
                        int commaIdx = url.indexOf(',');
                        return commaIdx >= 0 ? url.substring(commaIdx + 1) : url;
                    }
                    return null;
                })
                .filter(img -> img != null && !img.isBlank())
                .toList();
    }
}
