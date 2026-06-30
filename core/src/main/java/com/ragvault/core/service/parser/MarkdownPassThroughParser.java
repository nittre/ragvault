package com.ragvault.core.service.parser;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 마크다운/플레인텍스트 패스스루 파서.
 *
 * .md/.markdown/.txt 는 별도 추출 없이 UTF-8 텍스트를 그대로 통과시킨다
 * (기존 FAQ 마크다운 동작과 100% 동일하게 유지).
 */
@Component
public class MarkdownPassThroughParser implements DocumentParser {

    @Override
    public boolean supports(String extension) {
        return "md".equals(extension) || "markdown".equals(extension) || "txt".equals(extension);
    }

    @Override
    public ParsedDocument parse(byte[] bytes, String filename) {
        return ParsedDocument.ofText(new String(bytes, StandardCharsets.UTF_8));
    }
}
