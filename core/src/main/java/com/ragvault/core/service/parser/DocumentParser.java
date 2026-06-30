package com.ragvault.core.service.parser;

/**
 * 단일 포맷군을 마크다운으로 정규화하는 파서.
 *
 * 구현체는 Spring 빈으로 등록되어 {@link DocumentParserRouter}가 확장자 기준으로 라우팅한다.
 */
public interface DocumentParser {

    /**
     * 이 파서가 처리할 수 있는 확장자인지 여부.
     *
     * @param extension 소문자 확장자 (점 제외, 예: "docx", "pdf")
     */
    boolean supports(String extension);

    /**
     * 바이트 → 정규화된 {@link ParsedDocument}.
     *
     * @param bytes    원본 파일 바이트
     * @param filename 원본 파일명 (Tika 타입 힌트/메타데이터용)
     */
    ParsedDocument parse(byte[] bytes, String filename) throws Exception;
}
