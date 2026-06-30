package com.ragvault.core.service.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 확장자 기준으로 적합한 {@link DocumentParser}를 선택해 위임하는 라우터.
 *
 * 등록된 모든 파서 빈을 주입받아, {@link DocumentParser#supports(String)}가
 * true인 첫 파서로 라우팅한다. 미지원 확장자는 명확한 예외를 던진다.
 */
@Slf4j
@Service
public class DocumentParserRouter {

    /** 지원 확장자 — Runner/Controller 의 파일 필터링에 재사용. */
    public static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of("md", "markdown", "txt", "docx", "xlsx", "pptx", "doc", "ppt", "xls", "csv", "pdf");

    private final List<DocumentParser> parsers;

    public DocumentParserRouter(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    /** 파일명 확장자가 지원 대상인지 여부. */
    public boolean isSupported(String filename) {
        return SUPPORTED_EXTENSIONS.contains(extensionOf(filename));
    }

    /**
     * 바이트 → 정규화된 마크다운 문서.
     *
     * @throws IllegalArgumentException 미지원 확장자
     */
    public ParsedDocument parse(byte[] bytes, String filename) throws Exception {
        String ext = extensionOf(filename);
        DocumentParser parser = parsers.stream()
                .filter(p -> p.supports(ext))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 파일 형식: ." + ext));
        return parser.parse(bytes, filename);
    }

    /** 파일명에서 소문자 확장자 추출 (점 제외). */
    public static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase();
    }
}
