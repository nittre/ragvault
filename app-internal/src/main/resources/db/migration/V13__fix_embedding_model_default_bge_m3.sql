-- V13: embedding_model 기본값 bge-m3 으로 정정
--
-- 배경: V8에서 mxbai-embed-large(1024d)로 DEFAULT를 변경했으나,
--   실제 채택 모델은 bge-m3(1024d, BAAI 다국어 특화)로 확정됨.
--   ChunkingService.EMBEDDING_MODEL 상수도 bge-m3 이므로 DEFAULT와 일치시킴.
-- 영향: 신규 청크 INSERT 시 embedding_model 미지정 케이스 일관성 확보.
--   (ChunkingService는 항상 명시적으로 값을 전달하므로 실제 운영 영향 없음)

ALTER TABLE document_chunks
    ALTER COLUMN embedding_model SET DEFAULT 'bge-m3';
