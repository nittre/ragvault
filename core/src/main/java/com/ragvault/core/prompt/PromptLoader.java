package com.ragvault.core.prompt;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * classpath:prompts/** 하위 텍스트 파일을 읽어 LLM 프롬프트 문자열로 반환한다.
 */
public final class PromptLoader {

    private PromptLoader() {}

    public static String load(String classpathLocation) {
        try {
            return new ClassPathResource(classpathLocation)
                    .getContentAsString(StandardCharsets.UTF_8)
                    .strip();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt resource: " + classpathLocation, e);
        }
    }
}
