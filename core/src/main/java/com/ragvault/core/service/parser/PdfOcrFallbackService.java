package com.ragvault.core.service.parser;

import com.ragvault.core.service.TesseractOcrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * PDF 텍스트 추출이 사실상 실패했을 때(예: 텍스트가 폰트가 아닌 벡터 윤곽선으로만
 * 그려진 PDF)의 폴백 — 페이지를 이미지로 렌더링한 뒤 Tesseract OCR로 텍스트를 복구한다.
 *
 * {@link OpenDataLoaderPdfParser}가 판정 후 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfOcrFallbackService {

    private final TesseractOcrService tesseractOcrService;

    /** OCR 처리 상한 — 대용량 PDF에서 지연이 과도해지는 것을 방지. */
    private static final int MAX_OCR_PAGES = 20;
    /** 렌더링 해상도 — 속도-정확도 균형. */
    private static final float RENDER_DPI = 200f;
    /** 문서 1건당 OCR 최대 처리 시간 — 업로드 요청이 동기 처리이므로 상한을 둔다. */
    private static final long TIMEOUT_SECONDS = 180;

    /** 동시 OCR 작업 수를 제한해 다발적 업로드 시 스레드가 무한정 늘어나는 것을 방지. */
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    /**
     * 타임아웃을 두고 OCR 폴백을 실행한다. 실패/타임아웃 시 빈 문자열을 반환하며
     * 호출자는 원본(빈약한) markdown을 그대로 쓰면 된다 — 업로드 자체를 막지 않는다.
     *
     * @param pdfBytes 원본 PDF 바이트
     * @param filename 로그용 파일명
     * @return 페이지 구분자로 합쳐진 OCR 마크다운. 실패/타임아웃 시 빈 문자열.
     */
    public String ocrPdfWithTimeout(byte[] pdfBytes, String filename) {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> ocrPdf(pdfBytes, filename), executor);
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("PDF '{}' OCR 폴백 타임아웃({}초 초과)", filename, TIMEOUT_SECONDS);
            return "";
        } catch (Exception e) {
            log.error("PDF '{}' OCR 폴백 실행 실패: {}", filename, e.getMessage(), e);
            return "";
        }
    }

    private String ocrPdf(byte[] pdfBytes, String filename) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int totalPages = document.getNumberOfPages();
            int pagesToProcess = Math.min(totalPages, MAX_OCR_PAGES);
            if (pagesToProcess < totalPages) {
                log.warn("PDF '{}' 총 {}페이지, OCR은 상한({})까지만 처리", filename, totalPages, MAX_OCR_PAGES);
            }

            StringBuilder md = new StringBuilder();
            for (int i = 0; i < pagesToProcess; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, RENDER_DPI, ImageType.RGB);
                String pageText = tesseractOcrService.ocr(image);
                md.append("\n\n## Page ").append(i + 1).append("\n\n").append(pageText);
            }
            return md.toString().strip();
        } catch (Exception e) {
            log.error("PDF OCR 폴백 실패 '{}': {}", filename, e.getMessage(), e);
            return "";
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
