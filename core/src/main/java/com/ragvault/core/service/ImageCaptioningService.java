package com.ragvault.core.service;

import com.ragvault.core.service.parser.ParsedDocument.ExtractedImage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.stereotype.Service;

/**
 * 추출된 이미지 → Ollama 비전 모델 캡션 생성.
 *
 * - 비전 모델은 기본 chat 모델(qwen2.5:3b)과 별도 설정(widget.knowledge.vision-model).
 * - 모델 미설정("") 또는 호출 실패 시 캡션 없이 빈 문자열 반환(기동 차단 없음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageCaptioningService {

    private final ChatClient chatClient;

    private static final String CAPTION_PROMPT =
            "이 이미지를 RAG 검색에 활용할 수 있도록 상세히 설명하세요. " +
            "- UI 화면이라면: 버튼 이름·색상·위치, 입력 폼, 메뉴 항목, 화면 제목 등 주요 UI 요소를 모두 기술하세요. " +
            "- 표·차트·도표라면: 수치와 항목명을 텍스트로 요약하세요. " +
            "- 텍스트가 포함된 이미지라면: 핵심 문구를 그대로 옮기세요. " +
            "300자 이내로 답변하세요.";

    /**
     * 이미지 → 캡션 텍스트 (실패 시 빈 문자열).
     *
     * @param image       추출된 이미지
     * @param visionModel 사용할 Ollama 비전 모델명 (예: "qwen2.5vl:7b")
     */
    public String caption(ExtractedImage image, String visionModel) {
        if (visionModel == null || visionModel.isBlank()) {
            return "";
        }
        try {
            MimeType mimeType = MimeType.valueOf(
                    image.mimeType() != null ? image.mimeType() : "image/png");
            ByteArrayResource resource = new ByteArrayResource(image.bytes());

            return chatClient.prompt()
                    .options(OllamaOptions.builder().model(visionModel).build())
                    .user(u -> u.text(CAPTION_PROMPT).media(mimeType, resource))
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("이미지 캡셔닝 실패 (모델={}): {}", visionModel, e.getMessage());
            return "";
        }
    }
}
