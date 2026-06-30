package com.ragvault.core.service.parser;

import java.util.List;
import java.util.Map;

/**
 * 파서가 모든 입력 포맷을 정규화한 결과.
 *
 * 모든 {@link DocumentParser}는 입력(docx/xlsx/pptx/pdf/md ...)을
 * <b>마크다운 문자열</b>로 정규화하고, 본문에 인라인할 수 없는 임베디드 이미지는
 * {@link ExtractedImage} 목록으로 분리해 반환한다.
 *
 * 후속 단계(인입 서비스)가 이미지를 멀티모달 캡셔닝하여 마크다운에 합친 뒤
 * 기존 청킹/임베딩 흐름에 합류시킨다.
 */
public record ParsedDocument(String markdown, List<ExtractedImage> images, Map<String, Object> metadata) {

    /**
     * 추출된 임베디드 이미지 (캡셔닝 대상).
     *
     * @param bytes        원본 이미지 바이트
     * @param mimeType     image/png, image/jpeg 등
     * @param locationHint 본문 내 위치 힌트(파일명/페이지 등, nullable) — 디버깅·정렬용
     */
    public record ExtractedImage(byte[] bytes, String mimeType, String locationHint) {}

    /** 이미지 없는 텍스트 전용 결과. */
    public static ParsedDocument ofText(String markdown) {
        return new ParsedDocument(markdown != null ? markdown : "", List.of(), Map.of());
    }
}
