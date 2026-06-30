package com.ragvault.widget.controller;

import com.ragvault.core.repository.DocumentChunkRepository;
import com.ragvault.widget.service.AuditLogService;
import com.ragvault.widget.service.FaqChunkingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FAQ 마크다운 파일 CRUD + 임베딩 관리 엔드포인트.
 *
 * /admin/** 는 SecurityConfig 에서 JWT 인증 필수로 보호됨.
 * path traversal 방어: fileId에 ".." 또는 "/" 포함 시 400 반환.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/faq")
@RequiredArgsConstructor
public class FaqAdminController {

    private final FaqChunkingService chunkingService;
    private final DocumentChunkRepository chunkRepository;
    private final AuditLogService auditLogService;

    @Value("${widget.faq.directory:faq}")
    private String faqDirectory;

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    record FaqFileInfo(String name, long sizeBytes, String lastModified) {}

    record FaqFileDetail(String name, String content) {}

    record CreateFaqRequest(String name, String content) {}

    record UpdateFaqRequest(String content) {}

    // -------------------------------------------------------------------------
    // GET /admin/faq — 전체 .md 파일 목록 (파일명 정렬)
    // -------------------------------------------------------------------------

    @GetMapping
    public ResponseEntity<?> listFiles() {
        Path faqDir = Paths.get(faqDirectory);
        if (!Files.isDirectory(faqDir)) {
            log.warn("FAQ directory not found: {}", faqDir.toAbsolutePath());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "FAQ directory not found: " + faqDir.toAbsolutePath()));
        }

        try (var stream = Files.list(faqDir)) {
            List<FaqFileInfo> files = stream
                    .filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .map(p -> {
                        try {
                            long size = Files.size(p);
                            Instant modified = Files.getLastModifiedTime(p).toInstant();
                            String iso = DateTimeFormatter.ISO_INSTANT
                                    .withZone(ZoneOffset.UTC)
                                    .format(modified);
                            return new FaqFileInfo(p.getFileName().toString(), size, iso);
                        } catch (IOException e) {
                            log.warn("Cannot read metadata for {}: {}", p, e.getMessage());
                            return new FaqFileInfo(p.getFileName().toString(), -1L, null);
                        }
                    })
                    .toList();
            return ResponseEntity.ok(files);
        } catch (IOException e) {
            log.error("Failed to list FAQ directory: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to list FAQ directory: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // GET /admin/faq/{fileId} — 특정 파일 내용
    // -------------------------------------------------------------------------

    @GetMapping("/{fileId}")
    public ResponseEntity<?> getFile(@PathVariable String fileId) {
        if (isPathTraversal(fileId)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid fileId: path traversal not allowed"));
        }

        Path filePath = resolveFilePath(fileId);
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }

        try {
            String content = Files.readString(filePath);
            return ResponseEntity.ok(new FaqFileDetail(filePath.getFileName().toString(), content));
        } catch (IOException e) {
            log.error("Failed to read FAQ file {}: {}", fileId, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to read file: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // POST /admin/faq — 새 FAQ 파일 생성 + 임베딩
    // -------------------------------------------------------------------------

    @PostMapping
    public ResponseEntity<?> createFile(@RequestBody CreateFaqRequest request,
                                         Authentication authentication,
                                         HttpServletRequest httpRequest) {
        if (request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        if (isPathTraversal(request.name())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid name: path traversal not allowed"));
        }

        String normalizedName = normalizeMdName(request.name());
        Path faqDir = Paths.get(faqDirectory);
        if (!Files.isDirectory(faqDir)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "FAQ directory not found: " + faqDir.toAbsolutePath()));
        }

        Path filePath = faqDir.resolve(normalizedName);
        if (Files.exists(filePath)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "File already exists: " + normalizedName));
        }

        try {
            Files.writeString(filePath, request.content() != null ? request.content() : "");
            chunkingService.ingest(normalizedName, request.content() != null ? request.content() : "");
            log.info("FAQ created and ingested: {}", normalizedName);
            String actor = authentication != null ? authentication.getName() : "unknown";
            auditLogService.log(actor, "FAQ_CREATE", "faq_file", normalizedName, null,
                    httpRequest.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("name", normalizedName, "status", "created"));
        } catch (IOException e) {
            log.error("Failed to create FAQ file {}: {}", normalizedName, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to create file: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // PUT /admin/faq/{fileId} — FAQ 파일 내용 수정 + 재임베딩
    // -------------------------------------------------------------------------

    @PutMapping("/{fileId}")
    public ResponseEntity<?> updateFile(@PathVariable String fileId,
                                         @RequestBody UpdateFaqRequest request,
                                         Authentication authentication,
                                         HttpServletRequest httpRequest) {
        if (isPathTraversal(fileId)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid fileId: path traversal not allowed"));
        }

        Path filePath = resolveFilePath(fileId);
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }

        try {
            String content = request.content() != null ? request.content() : "";
            Files.writeString(filePath, content);
            chunkingService.ingest(filePath.getFileName().toString(), content);
            log.info("FAQ updated and re-ingested: {}", fileId);
            String actor = authentication != null ? authentication.getName() : "unknown";
            auditLogService.log(actor, "FAQ_UPDATE", "faq_file",
                    filePath.getFileName().toString(), null, httpRequest.getRemoteAddr());
            return ResponseEntity.ok(Map.of("name", filePath.getFileName().toString(), "status", "updated"));
        } catch (IOException e) {
            log.error("Failed to update FAQ file {}: {}", fileId, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to update file: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // DELETE /admin/faq/{fileId} — 파일 삭제 + 임베딩 삭제
    // -------------------------------------------------------------------------

    @DeleteMapping("/{fileId}")
    public ResponseEntity<?> deleteFile(@PathVariable String fileId,
                                         Authentication authentication,
                                         HttpServletRequest httpRequest) {
        if (isPathTraversal(fileId)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid fileId: path traversal not allowed"));
        }

        Path filePath = resolveFilePath(fileId);
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }

        String actualName = filePath.getFileName().toString();
        try {
            Files.delete(filePath);
            chunkRepository.deleteBySourceTableAndSourceId("faq_markdown", actualName);
            log.info("FAQ deleted: {}", actualName);
            String actor = authentication != null ? authentication.getName() : "unknown";
            auditLogService.log(actor, "FAQ_DELETE", "faq_file", actualName, null,
                    httpRequest.getRemoteAddr());
            return ResponseEntity.ok(Map.of("name", actualName, "status", "deleted"));
        } catch (IOException e) {
            log.error("Failed to delete FAQ file {}: {}", fileId, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to delete file: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // POST /admin/faq/reload — 전체 재적재 (기존 유지)
    // -------------------------------------------------------------------------

    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        Path faqDir = Paths.get(faqDirectory);
        if (!Files.isDirectory(faqDir)) {
            log.warn("FAQ directory not found: {}", faqDir.toAbsolutePath());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "FAQ directory not found: " + faqDir.toAbsolutePath()));
        }

        List<String> loaded = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        try (var stream = Files.list(faqDir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(path -> {
                        String fileId = path.getFileName().toString();
                        try {
                            String content = Files.readString(path);
                            chunkingService.ingest(fileId, content);
                            loaded.add(fileId);
                            log.info("FAQ reloaded: {}", fileId);
                        } catch (IOException e) {
                            log.error("Failed to read FAQ file {}: {}", fileId, e.getMessage());
                            failed.add(fileId);
                        }
                    });
        } catch (IOException e) {
            log.error("Failed to list FAQ directory: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to list FAQ directory: " + e.getMessage()));
        }

        return ResponseEntity.ok(Map.of(
                "loaded", loaded,
                "failed", failed,
                "total", loaded.size()
        ));
    }

    // -------------------------------------------------------------------------
    // POST /admin/faq/{fileId}/reload — 단일 파일 재적재
    // -------------------------------------------------------------------------

    @PostMapping("/{fileId}/reload")
    public ResponseEntity<?> reloadSingle(@PathVariable String fileId) {
        if (isPathTraversal(fileId)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid fileId: path traversal not allowed"));
        }

        Path filePath = resolveFilePath(fileId);
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }

        String actualName = filePath.getFileName().toString();
        try {
            String content = Files.readString(filePath);
            chunkingService.ingest(actualName, content);
            log.info("FAQ single reloaded: {}", actualName);
            return ResponseEntity.ok(Map.of("name", actualName, "status", "reloaded"));
        } catch (IOException e) {
            log.error("Failed to reload FAQ file {}: {}", fileId, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to reload file: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // 헬퍼
    // -------------------------------------------------------------------------

    /**
     * path traversal 방어: ".." 또는 "/" 포함 여부 검사.
     */
    private boolean isPathTraversal(String name) {
        return name != null && (name.contains("..") || name.contains("/") || name.contains("\\"));
    }

    /**
     * fileId → 실제 파일 경로 해석.
     * fileId 가 .md 로 끝나지 않으면 .md 를 붙인다.
     */
    private Path resolveFilePath(String fileId) {
        String normalized = normalizeMdName(fileId);
        return Paths.get(faqDirectory).resolve(normalized);
    }

    /**
     * 파일명이 .md 로 끝나지 않으면 .md 를 붙인다.
     */
    private String normalizeMdName(String name) {
        return name.endsWith(".md") ? name : name + ".md";
    }
}
