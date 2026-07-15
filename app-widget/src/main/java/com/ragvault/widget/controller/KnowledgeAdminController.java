package com.ragvault.widget.controller;

import com.ragvault.core.repository.DocumentChunkRepository;
import com.ragvault.core.service.parser.DocumentParserRouter;
import com.ragvault.widget.service.AuditLogService;
import com.ragvault.widget.service.KnowledgeIngestionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
 * 지식문서 CRUD + 임베딩 관리 엔드포인트.
 *
 * /api/admin/knowledge/** — SecurityConfig 에서 JWT 인증 필수.
 *
 * 텍스트(마크다운) CRUD:
 *   GET    /api/admin/knowledge          목록
 *   GET    /api/admin/knowledge/{docId}  내용
 *   POST   /api/admin/knowledge          마크다운 신규 생성
 *   PUT    /api/admin/knowledge/{docId}  마크다운 수정
 *   DELETE /api/admin/knowledge/{docId}  삭제
 *   POST   /api/admin/knowledge/{docId}/reload  단일 재적재
 *   POST   /api/admin/knowledge/reload   전체 재적재
 *
 * 바이너리 파일 업로드:
 *   POST   /api/admin/knowledge/upload   파일 업로드 + 인입 (multipart/form-data)
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/knowledge")
@RequiredArgsConstructor
public class KnowledgeAdminController {

    private final KnowledgeIngestionService ingestionService;
    private final DocumentChunkRepository chunkRepository;
    private final AuditLogService auditLogService;

    @Value("${widget.knowledge.directory:knowledge}")
    private String knowledgeDirectory;

    private static final long MAX_UPLOAD_BYTES = 30L * 1024 * 1024;

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    record DocFileInfo(String name, long sizeBytes, String lastModified) {}
    record DocFileDetail(String name, String content) {}
    record CreateDocRequest(String name, String content) {}
    record UpdateDocRequest(String content) {}

    // -------------------------------------------------------------------------
    // GET /api/admin/knowledge — 전체 지원 파일 목록
    // -------------------------------------------------------------------------

    @GetMapping
    public ResponseEntity<?> listFiles() {
        Path dir = knowledgeDir();
        if (!Files.isDirectory(dir)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "지식문서 디렉토리 없음: " + dir.toAbsolutePath()));
        }
        try (var stream = Files.list(dir)) {
            List<DocFileInfo> files = stream
                    .filter(p -> DocumentParserRouter.SUPPORTED_EXTENSIONS
                            .contains(DocumentParserRouter.extensionOf(p.getFileName().toString())))
                    .sorted()
                    .map(p -> {
                        try {
                            String iso = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
                                    .format(Files.getLastModifiedTime(p).toInstant());
                            return new DocFileInfo(p.getFileName().toString(), Files.size(p), iso);
                        } catch (IOException e) {
                            return new DocFileInfo(p.getFileName().toString(), -1L, null);
                        }
                    })
                    .toList();
            return ResponseEntity.ok(files);
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "디렉토리 조회 실패: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // GET /api/admin/knowledge/{docId} — 텍스트 파일 내용
    // -------------------------------------------------------------------------

    @GetMapping("/{docId}")
    public ResponseEntity<?> getFile(@PathVariable String docId) {
        if (isPathTraversal(docId)) return badTraversal();
        Path filePath = resolveFilePath(docId);
        if (!Files.exists(filePath)) return ResponseEntity.notFound().build();
        String ext = DocumentParserRouter.extensionOf(filePath.getFileName().toString());
        if (!"md".equals(ext) && !"txt".equals(ext)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "바이너리 파일은 텍스트로 조회할 수 없습니다: " + docId));
        }
        try {
            return ResponseEntity.ok(new DocFileDetail(
                    filePath.getFileName().toString(), Files.readString(filePath)));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "파일 읽기 실패: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/admin/knowledge — 마크다운 신규 생성
    // -------------------------------------------------------------------------

    @PostMapping
    public ResponseEntity<?> createMarkdown(@RequestBody CreateDocRequest req,
                                             Authentication auth,
                                             HttpServletRequest httpReq) {
        if (req.name() == null || req.name().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "name 필수"));
        if (isPathTraversal(req.name())) return badTraversal();

        String name = normalizeMdName(req.name());
        Path dir = knowledgeDir();
        if (!Files.isDirectory(dir))
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "지식문서 디렉토리 없음: " + dir.toAbsolutePath()));

        Path filePath = dir.resolve(name);
        if (Files.exists(filePath))
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "이미 존재하는 파일: " + name));

        try {
            String content = req.content() != null ? req.content() : "";
            Files.writeString(filePath, content);
            ingestionService.ingestMarkdown(name, content);
            auditLog(auth, "KNOWLEDGE_CREATE", name, httpReq);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("name", name, "status", "created"));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "파일 생성 실패: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // PUT /api/admin/knowledge/{docId} — 마크다운 수정
    // -------------------------------------------------------------------------

    @PutMapping("/{docId}")
    public ResponseEntity<?> updateMarkdown(@PathVariable String docId,
                                             @RequestBody UpdateDocRequest req,
                                             Authentication auth,
                                             HttpServletRequest httpReq) {
        if (isPathTraversal(docId)) return badTraversal();
        Path filePath = resolveFilePath(docId);
        if (!Files.exists(filePath)) return ResponseEntity.notFound().build();

        String ext = DocumentParserRouter.extensionOf(filePath.getFileName().toString());
        if (!"md".equals(ext) && !"txt".equals(ext))
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "바이너리 파일은 텍스트 PUT으로 수정할 수 없습니다"));

        try {
            String content = req.content() != null ? req.content() : "";
            Files.writeString(filePath, content);
            ingestionService.ingestMarkdown(filePath.getFileName().toString(), content);
            auditLog(auth, "KNOWLEDGE_UPDATE", filePath.getFileName().toString(), httpReq);
            return ResponseEntity.ok(
                    Map.of("name", filePath.getFileName().toString(), "status", "updated"));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "파일 수정 실패: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // DELETE /api/admin/knowledge/{docId}
    // -------------------------------------------------------------------------

    @DeleteMapping("/{docId}")
    public ResponseEntity<?> deleteFile(@PathVariable String docId,
                                         Authentication auth,
                                         HttpServletRequest httpReq) {
        if (isPathTraversal(docId)) return badTraversal();
        Path filePath = resolveFilePath(docId);
        if (!Files.exists(filePath)) return ResponseEntity.notFound().build();

        String name = filePath.getFileName().toString();
        try {
            Files.delete(filePath);
            chunkRepository.deleteBySourceTableAndSourceId(null, "knowledge_doc", name);
            auditLog(auth, "KNOWLEDGE_DELETE", name, httpReq);
            return ResponseEntity.ok(Map.of("name", name, "status", "deleted"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "파일 삭제 실패: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/admin/knowledge/upload — 바이너리 파일 업로드 + 인입
    // -------------------------------------------------------------------------

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file,
                                         Authentication auth,
                                         HttpServletRequest httpReq) {
        if (file == null || file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "파일이 비어 있습니다"));
        if (file.getSize() > MAX_UPLOAD_BYTES)
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "파일이 30MB를 초과합니다"));

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "파일명이 없습니다"));
        if (isPathTraversal(originalName))
            return ResponseEntity.badRequest().body(Map.of("error", "허용되지 않는 파일명"));

        String ext = DocumentParserRouter.extensionOf(originalName);
        if (!DocumentParserRouter.SUPPORTED_EXTENSIONS.contains(ext))
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "지원하지 않는 파일 형식: ." + ext));

        Path dir = knowledgeDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "디렉토리 생성 실패: " + e.getMessage()));
        }

        Path dest = dir.resolve(originalName);
        try {
            byte[] bytes = file.getBytes();
            Files.write(dest, bytes);
            ingestionService.ingestFile(originalName, bytes, originalName);
            auditLog(auth, "KNOWLEDGE_UPLOAD", originalName, httpReq);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("name", originalName, "status", "uploaded"));
        } catch (IOException e) {
            deleteQuietly(dest);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "파일 저장 실패: " + e.getMessage()));
        } catch (Throwable e) {
            // 파싱 단계에서 던져지는 NoClassDefFoundError 등 Error 는 Exception 이 아니므로
            // 별도로 잡아야 업로드 실패 시 디스크에 남은 파일을 정리할 수 있다.
            deleteQuietly(dest);
            log.error("업로드 파일 인입 실패 '{}': {}", originalName, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "파일 인입 실패: " + e.getMessage()));
        }
    }

    /** 업로드 실패 시 디스크에 저장된 파일을 정리한다 (목록 조회는 파일 시스템 기준이므로 남으면 안 됨). */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("업로드 실패 후 파일 정리 실패 '{}': {}", path, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/admin/knowledge/reload — 전체 재적재
    // -------------------------------------------------------------------------

    @PostMapping("/reload")
    public ResponseEntity<?> reloadAll() {
        Path dir = knowledgeDir();
        if (!Files.isDirectory(dir))
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "지식문서 디렉토리 없음: " + dir.toAbsolutePath()));

        List<String> loaded = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        try (var stream = Files.list(dir)) {
            stream.filter(p -> DocumentParserRouter.SUPPORTED_EXTENSIONS
                            .contains(DocumentParserRouter.extensionOf(p.getFileName().toString())))
                    .sorted()
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        String ext = DocumentParserRouter.extensionOf(name);
                        try {
                            if ("md".equals(ext) || "txt".equals(ext)) {
                                ingestionService.ingestMarkdown(name, Files.readString(path));
                            } else {
                                ingestionService.ingestFile(name, Files.readAllBytes(path), name);
                            }
                            loaded.add(name);
                        } catch (Exception e) {
                            log.error("재적재 실패 '{}': {}", name, e.getMessage());
                            failed.add(name);
                        }
                    });
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "디렉토리 조회 실패: " + e.getMessage()));
        }

        return ResponseEntity.ok(Map.of("loaded", loaded, "failed", failed, "total", loaded.size()));
    }

    // -------------------------------------------------------------------------
    // POST /api/admin/knowledge/{docId}/reload — 단일 재적재
    // -------------------------------------------------------------------------

    @PostMapping("/{docId}/reload")
    public ResponseEntity<?> reloadSingle(@PathVariable String docId) {
        if (isPathTraversal(docId)) return badTraversal();
        Path filePath = resolveFilePath(docId);
        if (!Files.exists(filePath)) return ResponseEntity.notFound().build();

        String name = filePath.getFileName().toString();
        String ext = DocumentParserRouter.extensionOf(name);
        try {
            if ("md".equals(ext) || "txt".equals(ext)) {
                ingestionService.ingestMarkdown(name, Files.readString(filePath));
            } else {
                ingestionService.ingestFile(name, Files.readAllBytes(filePath), name);
            }
            return ResponseEntity.ok(Map.of("name", name, "status", "reloaded"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "재적재 실패: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // 헬퍼
    // -------------------------------------------------------------------------

    private Path knowledgeDir() {
        return Paths.get(knowledgeDirectory);
    }

    private boolean isPathTraversal(String name) {
        return name != null && (name.contains("..") || name.contains("/") || name.contains("\\"));
    }

    private Path resolveFilePath(String docId) {
        return knowledgeDir().resolve(normalizeMdName(docId));
    }

    /** 확장자 없는 이름은 .md 추가 (마크다운 기본). */
    private String normalizeMdName(String name) {
        return name.contains(".") ? name : name + ".md";
    }

    private ResponseEntity<Map<String, Object>> badTraversal() {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "유효하지 않은 파일명: path traversal 불허"));
    }

    private void auditLog(Authentication auth, String action, String target,
                           HttpServletRequest req) {
        String actor = auth != null ? auth.getName() : "unknown";
        auditLogService.log(actor, action, "knowledge_doc", target, null, req.getRemoteAddr());
    }
}
