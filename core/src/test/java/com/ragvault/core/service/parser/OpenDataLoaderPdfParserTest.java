package com.ragvault.core.service.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link OpenDataLoaderPdfParser#isTextExtractionFailed} 판정 로직 단위 테스트.
 *
 * bytes 인자로는 실제 PDF가 아닌 더미 바이트를 사용한다 — 페이지 수 확인이 실패하면
 * 구현상 pageCount=1로 가정하므로, 페이지 수 의존 없이 문자 수 임계값만 검증할 수 있다.
 */
class OpenDataLoaderPdfParserTest {

    private final OpenDataLoaderPdfParser parser =
            new OpenDataLoaderPdfParser(mock(PdfOcrFallbackService.class));

    private static final byte[] DUMMY_BYTES = "not a real pdf".getBytes();

    @Test
    void 빈_마크다운은_실패로_판정한다() {
        assertThat(parser.isTextExtractionFailed("", DUMMY_BYTES, "empty.pdf")).isTrue();
    }

    @Test
    void 실제_사례처럼_이미지_참조만_있는_마크다운은_실패로_판정한다() {
        String markdown = "![image 1](images/imageFile1.png)\n\n![image 2](images/imageFile2.png)\n\n";
        assertThat(parser.isTextExtractionFailed(markdown, DUMMY_BYTES, "emoji-only.pdf")).isTrue();
    }

    @Test
    void 최소_절대_임계값_미만이면_실패로_판정한다() {
        String markdown = "x".repeat(19); // MIN_STRIPPED_CHARS(20) 미만
        assertThat(parser.isTextExtractionFailed(markdown, DUMMY_BYTES, "tiny.pdf")).isTrue();
    }

    @Test
    void 페이지당_평균_임계값_미만이면_실패로_판정한다() {
        String markdown = "x".repeat(29); // pageCount=1 가정 시 avgCharsPerPage=29 (<30), 전체도 500 미만
        assertThat(parser.isTextExtractionFailed(markdown, DUMMY_BYTES, "sparse.pdf")).isTrue();
    }

    @Test
    void 평균_임계값_경계값_이상이면_실패로_판정하지_않는다() {
        String markdown = "x".repeat(30); // avgCharsPerPage=30 (경계값, 실패 아님)
        assertThat(parser.isTextExtractionFailed(markdown, DUMMY_BYTES, "boundary.pdf")).isFalse();
    }

    @Test
    void 충분한_본문_텍스트가_있으면_실패로_판정하지_않는다() {
        String markdown = "# 제목\n\n" + "정상적인 본문 텍스트입니다. ".repeat(40);
        assertThat(parser.isTextExtractionFailed(markdown, DUMMY_BYTES, "normal.pdf")).isFalse();
    }
}
