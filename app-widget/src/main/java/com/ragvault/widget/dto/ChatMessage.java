package com.ragvault.widget.dto;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OpenAI 호환 chat message DTO.
 * widget/chat.html 이 choices[0].message.content 를 읽는다.
 */
public record ChatMessage(String role, Object content) {

    /** 텍스트 내용만 추출 (멀티모달 파트 미지원 — 텍스트만) */
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
}
