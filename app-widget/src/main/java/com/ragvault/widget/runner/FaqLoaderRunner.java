package com.ragvault.widget.runner;

import com.ragvault.widget.service.FaqChunkingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 부트스트랩 시 FAQ 마크다운 파일 자동 적재.
 *
 * widget.faq.auto-load=true(기본) 이면 시작 시 faq/ 디렉토리의 .md 파일 전체 임베딩.
 * 이미 동일 content_hash 로 저장된 청크는 UPSERT 로 스킵 (멱등성).
 *
 * Ollama가 기동되지 않은 환경(테스트 등)에서는 에러 로그만 남기고 계속.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FaqLoaderRunner implements ApplicationRunner {

    private final FaqChunkingService chunkingService;

    @Value("${widget.faq.directory:faq}")
    private String faqDirectory;

    @Value("${widget.faq.auto-load:true}")
    private boolean autoLoad;

    @Override
    public void run(ApplicationArguments args) {
        if (!autoLoad) {
            log.info("FAQ auto-load disabled (widget.faq.auto-load=false)");
            return;
        }

        Path faqDir = Paths.get(faqDirectory);
        if (!Files.isDirectory(faqDir)) {
            log.warn("FAQ directory not found, skipping auto-load: {}", faqDir.toAbsolutePath());
            return;
        }

        log.info("Starting FAQ auto-load from: {}", faqDir.toAbsolutePath());
        try (var stream = Files.list(faqDir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(this::loadFile);
        } catch (IOException e) {
            log.error("Failed to list FAQ directory: {}", e.getMessage());
        }
    }

    private void loadFile(Path path) {
        String fileId = path.getFileName().toString();
        try {
            String content = Files.readString(path);
            chunkingService.ingest(fileId, content);
            log.info("FAQ loaded: {}", fileId);
        } catch (Exception e) {
            // Ollama 미기동 등 외부 의존성 실패 → 서버 기동 차단하지 않음
            log.error("Failed to load FAQ '{}': {}", fileId, e.getMessage());
        }
    }
}
