package com.ragvault.core.service.parser;

import lombok.extern.slf4j.Slf4j;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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
public class OpenDataLoaderPdfParser implements DocumentParser {

    private static final int MAX_IMAGES = 50;

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
            log.debug("ODL parsed PDF '{}': {} chars, {} images", filename, markdown.length(), images.size());
            return new ParsedDocument(markdown, images,
                    Map.of("parser", "opendataloader-pdf", "filename", filename != null ? filename : ""));
        } finally {
            deleteRecursively(workDir);
        }
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
