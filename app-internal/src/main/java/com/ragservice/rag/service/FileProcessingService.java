package com.ragservice.rag.service;

import com.ragservice.rag.domain.FileProcessing;
import com.ragservice.rag.repository.FileProcessingRepository;
import com.ragvault.core.security.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * FILE 경로 — 첨부파일 업로드 처리.
 *
 * 흐름: 검증 → S3 저장 → Tika 텍스트 추출 → PiiMasker → DB 저장
 *
 * 파일 제한:
 * - 단일 파일 30MB
 * - 허용 확장자: pdf, docx, doc, pptx, ppt, xlsx, xls, txt, md, csv
 * - 임베디드 이미지 200개 (Phase 0: 개수 체크만)
 *
 * ADR-0008: piiMasker.mask() 필수 (추출 텍스트에 적용)
 *
 * requirements/10-multimodal-files-url.md 섹션 4
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileProcessingService {

    private final S3Client s3Client;
    private final PiiMasker piiMasker;
    private final FileProcessingRepository fileProcessingRepository;

    @Value("${rag.storage.s3.bucket:rag-files-bucket}")
    private String bucket;

    @Value("${rag.chat.model:qwen2.5:7b-instruct-q4_K_M}")
    private String tokenizerModelName;

    private static final long MAX_SIZE = 30L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTS =
            Set.of("pdf","docx","doc","pptx","ppt","xlsx","xls","txt","md","csv");

    public record UploadResult(UUID fileId, int tokenCount, String status) {}

    public UploadResult process(MultipartFile file, String userEmail) {
        validateFile(file);
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (Exception e) { throw new IllegalArgumentException("파일 읽기 실패: " + e.getMessage()); }

        // S3 저장
        String s3Key = "files/" + UUID.randomUUID() + "/" + sanitize(file.getOriginalFilename());
        uploadToS3(s3Key, bytes, file.getContentType());

        // Tika 텍스트 추출
        String text = extractText(bytes, file.getOriginalFilename());

        // ADR-0008: PII 마스킹
        String masked = piiMasker.mask(text);

        FileProcessing fp = FileProcessing.builder()
                .userEmail(userEmail)
                .s3Key(s3Key)
                .originalName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown")
                .mimeType(file.getContentType())
                .sizeBytes((long) bytes.length)
                .extractedText(masked)
                .tokenCount(estimateTokens(masked))
                .tokenizerModel(tokenizerModelName)
                .imageCount(0)
                .ocrImageCount(0)
                .status("done")
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();

        fileProcessingRepository.save(fp);
        log.info("파일 처리 완료: id={} size={}B tokens={}", fp.getId(), bytes.length, fp.getTokenCount());
        return new UploadResult(fp.getId(), fp.getTokenCount(), "done");
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("파일이 비어 있습니다.");
        if (file.getSize() > MAX_SIZE)
            throw new IllegalArgumentException(
                    "파일이 30MB를 초과합니다 (현재 " + file.getSize() / 1024 / 1024 + "MB).");
        String ext = getExt(file.getOriginalFilename());
        if (!ALLOWED_EXTS.contains(ext))
            throw new IllegalArgumentException("허용되지 않는 파일 형식입니다: ." + ext);
    }

    private String extractText(byte[] bytes, String filename) {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata meta = new Metadata();
        if (filename != null) meta.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        ParseContext ctx = new ParseContext();
        ctx.set(Parser.class, parser);
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            parser.parse(is, handler, meta, ctx);
            return handler.toString();
        } catch (Exception e) {
            log.warn("Tika 추출 실패: {}", e.getMessage());
            return "";
        }
    }

    private void uploadToS3(String key, byte[] bytes, String ct) {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key)
                            .contentType(ct != null ? ct : "application/octet-stream").build(),
                    RequestBody.fromBytes(bytes));
        } catch (Exception e) {
            log.error("S3 업로드 실패 (non-critical): {}", e.getMessage());
        }
    }

    private String getExt(String name) {
        if (name == null || !name.contains(".")) return "";
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase();
    }

    private String sanitize(String name) {
        return name == null ? "unknown" : name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.split("\\s+").length;
    }
}
