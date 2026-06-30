package com.ragservice.rag.controller;

import com.ragservice.rag.service.FileProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * POST /v1/files — OpenAI 호환 파일 업로드.
 * multipart/form-data, 최대 30MB (application.yml: spring.servlet.multipart.max-file-size=30MB)
 *
 * requirements/10-multimodal-files-url.md 섹션 4
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class FileController {

    private final FileProcessingService fileProcessingService;

    @PostMapping("/files")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail) {

        log.debug("파일 업로드: name={}, size={}", file.getOriginalFilename(), file.getSize());
        try {
            FileProcessingService.UploadResult result =
                    fileProcessingService.process(file, userEmail != null ? userEmail : "unknown");
            return ResponseEntity.ok(Map.of(
                    "id", result.fileId().toString(),
                    "object", "file",
                    "status", result.status(),
                    "token_count", result.tokenCount()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/files/{fileId}")
    public ResponseEntity<Void> deleteFile(@PathVariable String fileId) {
        log.info("파일 삭제 요청: {}", fileId);
        // Phase 0: S3 lifecycle이 24h 후 자동 삭제
        return ResponseEntity.noContent().build();
    }
}
