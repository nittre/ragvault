package com.ragvault.core.service.parser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * opendataloader-pdf 기반 PDF 파서.
 *
 * opendataloader API는 <b>파일 경로 입력 + outputFolder 파일 출력</b> 방식이므로,
 * 바이트를 임시 디렉토리에 쓰고 → {@link OpenDataLoaderPDF#processFile} 실행 →
 * 생성된 .md(표 구조 보존)와 추출된 이미지 파일들을 읽어 {@link ParsedDocument}로 정규화한다.
 * 처리 후 임시 디렉토리는 항상 정리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenDataLoaderPdfParser implements DocumentParser {

    private static final int MAX_IMAGES = 50;

    /** 이미지 마크다운 문법(alt 텍스트 포함) 제거용 — 순수 텍스트 길이 판정에 사용. */
    private static final Pattern IMAGE_MARKDOWN = Pattern.compile("!\\[[^]]*]\\([^)]*\\)");
    /** 이 미만이면 사실상 텍스트가 없다고 판단(절대 최소치). */
    private static final int MIN_STRIPPED_CHARS = 20;
    /** 페이지당 평균 문자 수가 이 미만이면서 전체도 짧으면 텍스트 추출 실패로 판단. */
    private static final int MIN_AVG_CHARS_PER_PAGE = 30;
    private static final int MAX_TOTAL_CHARS_FOR_AVG_CHECK = 500;

    private final PdfOcrFallbackService ocrFallbackService;

    @Override
    public boolean supports(String extension) {
        return "pdf".equals(extension);
    }

    @Override
    public ParsedDocument parse(byte[] bytes, String filename) throws Exception {
        Path workDir = Files.createTempDirectory("odl-pdf-");
        Path imageDir = workDir.resolve("images");
        Path pdfFile = workDir.resolve("input.pdf");
        try {
            Files.createDirectories(imageDir);
            Files.write(pdfFile, bytes);

            Config config = new Config();
            config.setOutputFolder(workDir.toString());
            config.setGenerateMarkdown(true);
            config.setGenerateJSON(false);   // 기본 true — md만 필요하므로 명시적 off
            config.setGenerateHtml(false);
            config.setGeneratePDF(false);
            config.setAddImageToMarkdown(true);
            config.setImageOutput(Config.IMAGE_OUTPUT_EXTERNAL);
            config.setImageDir(imageDir.toString());
            config.setImageFormat(Config.IMAGE_FORMAT_PNG);

            OpenDataLoaderPDF.processFile(pdfFile.toString(), config);

            String markdown = readGeneratedMarkdown(workDir);
            List<ParsedDocument.ExtractedImage> images = readExtractedImages(imageDir);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("parser", "opendataloader-pdf");
            metadata.put("filename", filename != null ? filename : "");

            if (isTextExtractionFailed(markdown, bytes, filename)) {
                log.info("PDF '{}' 텍스트 추출 사실상 실패로 판정, OCR 폴백 시도", filename);
                String ocrMarkdown = ocrFallbackService.ocrPdfWithTimeout(bytes, filename);
                metadata.put("ocrFallbackAttempted", true);
                if (!ocrMarkdown.isBlank()) {
                    markdown = ocrMarkdown;
                    metadata.put("parser", "opendataloader-pdf+tesseract-ocr-fallback");
                    metadata.put("ocrFallbackSucceeded", true);
                } else {
                    metadata.put("ocrFallbackSucceeded", false);
                    // markdown은 원본(빈약한) 값 그대로 유지 — OCR 실패해도 업로드 자체는 막지 않는다.
                }
            }

            log.debug("ODL parsed PDF '{}': {} chars, {} images", filename, markdown.length(), images.size());
            return new ParsedDocument(markdown, images, Map.copyOf(metadata));
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * PDF 텍스트 추출이 사실상 실패했는지 판정한다 (예: 텍스트가 폰트가 아닌 벡터
     * 윤곽선으로만 그려진 PDF — opendataloader-pdf-core/PDFBox 어떤 설정으로도
     * 복구되지 않는 근본적 한계).
     */
    // package-private (테스트에서 직접 호출)
    boolean isTextExtractionFailed(String markdown, byte[] bytes, String filename) {
        String stripped = IMAGE_MARKDOWN.matcher(markdown == null ? "" : markdown)
                .replaceAll("")
                .strip();
        int strippedLength = stripped.length();

        int pageCount = 1;
        try (PDDocument document = Loader.loadPDF(bytes)) {
            pageCount = Math.max(document.getNumberOfPages(), 1);
        } catch (Exception e) {
            log.warn("PDF '{}' 페이지 수 확인 실패, 1페이지로 가정: {}", filename, e.getMessage());
        }

        double avgCharsPerPage = strippedLength / (double) pageCount;
        boolean failed = strippedLength < MIN_STRIPPED_CHARS
                || (avgCharsPerPage < MIN_AVG_CHARS_PER_PAGE && strippedLength < MAX_TOTAL_CHARS_FOR_AVG_CHECK);

        log.info("PDF '{}' 텍스트 추출 판정: stripped={} chars, pages={}, avg={}, failed={}",
                filename, strippedLength, pageCount, avgCharsPerPage, failed);
        return failed;
    }

    /** outputFolder 에서 생성된 .md 파일을 찾아 읽는다 (파일명 규칙 비의존). */
    private String readGeneratedMarkdown(Path outputFolder) throws IOException {
        try (var stream = Files.list(outputFolder)) {
            Path md = stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .findFirst()
                    .orElse(null);
            if (md == null) {
                log.warn("opendataloader가 .md 를 생성하지 않음: {}", outputFolder);
                return "";
            }
            return Files.readString(md, StandardCharsets.UTF_8);
        }
    }

    /** imageDir 의 추출 이미지 파일들을 바이트로 읽는다 (상한 적용). */
    private List<ParsedDocument.ExtractedImage> readExtractedImages(Path imageDir) {
        List<ParsedDocument.ExtractedImage> images = new ArrayList<>();
        if (!Files.isDirectory(imageDir)) return images;
        try (var stream = Files.list(imageDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .limit(MAX_IMAGES)
                    .toList();
            for (Path p : files) {
                try {
                    byte[] data = Files.readAllBytes(p);
                    if (data.length == 0) continue;
                    String name = p.getFileName().toString();
                    String mime = name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".jpeg")
                            ? "image/jpeg" : "image/png";
                    images.add(new ParsedDocument.ExtractedImage(data, mime, name));
                } catch (IOException e) {
                    log.warn("PDF 추출 이미지 읽기 실패 {}: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("PDF 이미지 디렉토리 조회 실패: {}", e.getMessage());
        }
        return images;
    }

    private void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort 정리
                }
            });
        } catch (IOException e) {
            log.warn("임시 디렉토리 정리 실패 {}: {}", dir, e.getMessage());
        }
    }
}
