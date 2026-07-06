package com.ragvault.core.service;

import com.ragvault.core.prompt.PromptLoader;
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
            PromptLoader.load("prompts/image-captioning/caption.txt");

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
