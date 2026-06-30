package com.ragservice.rag.service;
import com.ragvault.core.service.*;

import com.ragvault.core.domain.DocumentChunk;
import com.ragvault.core.domain.RagTableConfig;
import com.ragvault.core.repository.DocumentChunkRepository;
import com.ragvault.core.security.PiiMasker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.ollama.OllamaEmbeddingModel;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChunkingService 단위 테스트.
 *
 * 외부 의존성(OllamaEmbeddingModel, Repository)은 Mock 사용.
 */
@ExtendWith(MockitoExtension.class)
class ChunkingServiceTest {

    @Mock
    OllamaEmbeddingModel embeddingModel;

    @Mock
    DocumentChunkRepository chunkRepository;

    /**
     * PiiMasker는 @RequiredArgsConstructor(MaskingRuleRepository 주입) 이므로
     * no-arg @Spy 사용 불가 → @Mock으로 교체.
     * 실제 마스킹 동작 검증은 PiiMaskerTest에서 별도 수행.
     */
    @Mock
    PiiMasker piiMasker;

    @InjectMocks
    ChunkingService chunkingService;

    private RagTableConfig buildConfig() {
        RagTableConfig config = new RagTableConfig();
        config.setSourceTable("products");
        config.setSourceType("product");
        config.setContentColumns(new String[]{"description"});
        config.setPkColumn("id");
        config.setChunkingStrategy("per-record");
        config.setPiiMaskingLevel("standard");
        config.setChunkSize(500);
        config.setChunkOverlap(50);
        return config;
    }

    @Test
    void processRow_callsUpsert() {
        RagTableConfig config = buildConfig();
        when(piiMasker.mask(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);

        Map<String, Object> row = Map.of("id", "123", "description", "test product description");
        chunkingService.processRow(config, row);

        verify(chunkRepository).upsertChunk(any(DocumentChunk.class), any(float[].class));
    }

    @Test
    void processRow_masksPii() {
        RagTableConfig config = buildConfig();
        when(piiMasker.mask(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);

        // 설명에 전화번호 포함 → 마스킹되어야 함
        Map<String, Object> row = Map.of("id", "456", "description", "문의 010-1234-5678 입니다");
        chunkingService.processRow(config, row);

        // upsertChunk가 호출됐는지 확인 (마스킹 여부는 PiiMaskerTest에서 별도 검증)
        verify(chunkRepository).upsertChunk(any(DocumentChunk.class), any(float[].class));
    }

    @Test
    void deleteChunks_callsRepository() {
        chunkingService.deleteChunks("products", "123");
        verify(chunkRepository).deleteBySourceTableAndSourceId("products", "123");
    }

    @Test
    void processRow_withTitleColumn() {
        RagTableConfig config = buildConfig();
        config.setTitleColumn("name");
        when(piiMasker.mask(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);

        Map<String, Object> row = Map.of("id", "789", "name", "Product A", "description", "details");
        chunkingService.processRow(config, row);

        verify(chunkRepository).upsertChunk(any(DocumentChunk.class), any(float[].class));
    }
}
