package com.ragvault.core.service.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Apache Tika 기반 Office/CSV 파서 — docx/xlsx/pptx/doc/ppt/xls/csv.
 *
 * - 본문 텍스트: {@link AutoDetectParser} + {@link BodyContentHandler}(무제한) 로 추출.
 *   표는 Tika가 셀을 공백/줄바꿈으로 평탄화한 텍스트로 포함된다.
 * - 임베디드 이미지: {@link EmbeddedDocumentExtractor} 로 image/* 만 분리 수집 →
 *   {@link ParsedDocument.ExtractedImage} (후속 멀티모달 캡셔닝 대상).
 *
 * app-internal {@code FileProcessingService.extractText()} 패턴을 재사용하되,
 * 이미지 추출을 추가하고 RAG 인입용으로 일반화했다.
 */
@Slf4j
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final Set<String> SUPPORTED =
            Set.of("docx", "xlsx", "pptx", "doc", "ppt", "xls", "csv");

    /** 단일 문서당 캡셔닝할 이미지 상한 (과도한 비전 호출 방지). */
    private static final int MAX_IMAGES = 50;

    @Override
    public boolean supports(String extension) {
        return SUPPORTED.contains(extension);
    }

    @Override
    public ParsedDocument parse(byte[] bytes, String filename) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata meta = new Metadata();
        if (filename != null) meta.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);

        List<ParsedDocument.ExtractedImage> images = new ArrayList<>();

        ParseContext ctx = new ParseContext();
        ctx.set(Parser.class, parser);
        ctx.set(EmbeddedDocumentExtractor.class, new ImageCollectingExtractor(images));

        try (InputStream is = new ByteArrayInputStream(bytes)) {
            parser.parse(is, handler, meta, ctx);
        }

        String markdown = handler.toString();
        log.debug("Tika parsed '{}': {} chars, {} images", filename, markdown.length(), images.size());
        return new ParsedDocument(markdown, images,
                Map.of("parser", "tika", "filename", filename != null ? filename : ""));
    }

    /**
     * 임베디드 객체 중 image/* 만 바이트로 수집하는 추출기.
     * 텍스트 핸들러에는 위임하지 않으므로(본문 추출은 상위 파서가 담당) 이미지 수집에만 집중.
     */
    private static final class ImageCollectingExtractor implements EmbeddedDocumentExtractor {
        private final List<ParsedDocument.ExtractedImage> sink;

        ImageCollectingExtractor(List<ParsedDocument.ExtractedImage> sink) {
            this.sink = sink;
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return sink.size() < MAX_IMAGES;
        }

        @Override
        public void parseEmbedded(InputStream stream, ContentHandler handler,
                                  Metadata metadata, boolean outputHtml) {
            String type = metadata.get(Metadata.CONTENT_TYPE);
            if (type == null || !type.startsWith("image/")) return;
            if (sink.size() >= MAX_IMAGES) return;
            try {
                byte[] data = stream.readAllBytes();
                if (data.length == 0) return;
                String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                sink.add(new ParsedDocument.ExtractedImage(data, type, name));
            } catch (Exception e) {
                log.warn("임베디드 이미지 추출 실패: {}", e.getMessage());
            }
        }
    }
}
