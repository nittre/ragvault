package com.ragvault.widget.runner;

import com.ragvault.core.service.parser.DocumentParserRouter;
import com.ragvault.widget.service.KnowledgeIngestionService;
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
 * 부트스트랩 시 지식문서 디렉토리 자동 적재.
 *
 * widget.knowledge.auto-load=true(기본)이면 시작 시 knowledge/ 디렉토리의
 * 지원 확장자 파일 전체 임베딩 (.md/.txt/.docx/.xlsx/.pptx/.pdf).
 * 멱등성: content_hash 기반 UPSERT — 내용 변경분만 갱신.
 *
 * Ollama 미기동 등 외부 의존성 실패 시 에러 로그만 남기고 서버 기동은 계속한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeLoaderRunner implements ApplicationRunner {

    private final KnowledgeIngestionService ingestionService;

    @Value("${widget.knowledge.directory:knowledge}")
    private String knowledgeDirectory;

    @Value("${widget.knowledge.auto-load:true}")
    private boolean autoLoad;

    @Override
    public void run(ApplicationArguments args) {
        if (!autoLoad) {
            log.info("지식문서 자동 적재 비활성화 (widget.knowledge.auto-load=false)");
            return;
        }

        Path dir = Paths.get(knowledgeDirectory);
        if (!Files.isDirectory(dir)) {
            log.warn("지식문서 디렉토리 없음, 자동 적재 건너뜀: {}", dir.toAbsolutePath());
            return;
        }

        log.info("지식문서 자동 적재 시작: {}", dir.toAbsolutePath());
        try (var stream = Files.list(dir)) {
            stream.filter(p -> DocumentParserRouter.SUPPORTED_EXTENSIONS
                            .contains(DocumentParserRouter.extensionOf(p.getFileName().toString())))
                    .sorted()
                    .forEach(this::loadFile);
        } catch (IOException e) {
            log.error("지식문서 디렉토리 조회 실패: {}", e.getMessage());
        }
    }

    private void loadFile(Path path) {
        String filename = path.getFileName().toString();
        String ext = DocumentParserRouter.extensionOf(filename);
        try {
            if ("md".equals(ext) || "txt".equals(ext)) {
                String content = Files.readString(path);
                ingestionService.ingestMarkdown(filename, content);
            } else {
                byte[] bytes = Files.readAllBytes(path);
                ingestionService.ingestFile(filename, bytes, filename);
            }
            log.info("지식문서 적재 완료: {}", filename);
        } catch (Exception e) {
            log.error("지식문서 적재 실패 '{}': {}", filename, e.getMessage());
        }
    }
}
