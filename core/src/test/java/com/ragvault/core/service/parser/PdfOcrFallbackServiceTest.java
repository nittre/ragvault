package com.ragvault.core.service.parser;

import com.ragvault.core.service.TesseractOcrServiceImpl;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link PdfOcrFallbackService} 통합 테스트 — 실제 Tesseract 바이너리를 호출한다.
 *
 * CI/로컬 환경에 tesseract가 없으면(tessdata 경로 미탐지) 스킵한다
 * ({@code brew install tesseract tesseract-lang} 등으로 로컬 설치 후 재실행 가능).
 */
class PdfOcrFallbackServiceTest {

    @Test
    void 렌더링된_페이지에서_텍스트를_복구한다() throws Exception {
        String tessDataPath = findTessDataPath();
        assumeTrue(tessDataPath != null, "tessdata를 찾을 수 없어 스킵 (brew install tesseract tesseract-lang)");

        PdfOcrFallbackService service = new PdfOcrFallbackService(newTesseractService(tessDataPath));
        byte[] pdfBytes = createSimpleTextPdf("HELLO WORLD");

        String result = service.ocrPdfWithTimeout(pdfBytes, "test.pdf");

        assertThat(result.toUpperCase()).contains("HELLO");
    }

    /** @Value 필드 주입 없이 직접 인스턴스화하므로 리플렉션으로 datapath를 설정한다. */
    private TesseractOcrServiceImpl newTesseractService(String tessDataPath) throws Exception {
        TesseractOcrServiceImpl impl = new TesseractOcrServiceImpl();
        Field field = TesseractOcrServiceImpl.class.getDeclaredField("tessDataPath");
        field.setAccessible(true);
        field.set(impl, tessDataPath);
        return impl;
    }

    private String findTessDataPath() {
        return Stream.of(
                        "/opt/homebrew/share/tessdata",
                        "/usr/local/share/tessdata",
                        "/usr/share/tessdata",
                        "/usr/share/tesseract-ocr/4.00/tessdata",
                        "/usr/share/tesseract-ocr/5/tessdata")
                .filter(p -> Files.isDirectory(Path.of(p)))
                .findFirst()
                .orElse(null);
    }

    private byte[] createSimpleTextPdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 48);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
