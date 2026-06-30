-- V8: document_chunks.embedding 차원 768 → 1024 (mxbai-embed-large 적용)
--
-- 배경: nomic-embed-text(768d) → mxbai-embed-large(1024d) 모델 교체.
--   mxbai-embed-large는 패시지 검색 특화 모델로 한국어/다국어 지원이 우수함.
-- 절차:
--   1. HNSW 인덱스 삭제 (타입 변경 전 필수)
--   2. 유니크 제약 삭제
--   3. 컬럼 타입 변경 vector(768) → vector(1024)
--   4. embedding_model 기본값 업데이트
--   5. 유니크 제약 재생성
--   6. HNSW 인덱스 재생성 (m=16, ef=64)

-- 1. 인덱스 삭제
DROP INDEX IF EXISTS idx_chunks_hnsw;

-- 2. 유니크 제약 삭제 (vector 컬럼 포함)
ALTER TABLE document_chunks
    DROP CONSTRAINT IF EXISTS document_chunks_source_table_source_id_chunk_index_embeddin_key;

-- 3. 기존 데이터 삭제 (차원 불일치로 USING 변환 불가)
DELETE FROM document_chunks;

-- 4. 컬럼 타입 변경
ALTER TABLE document_chunks
    ALTER COLUMN embedding TYPE vector(1024);

-- 5. embedding_model 기본값 변경
ALTER TABLE document_chunks
    ALTER COLUMN embedding_model SET DEFAULT 'mxbai-embed-large';

-- 6. 유니크 제약 재생성
ALTER TABLE document_chunks
    ADD CONSTRAINT document_chunks_source_table_source_id_chunk_index_embeddin_key
    UNIQUE (source_table, source_id, chunk_index, embedding_model);

-- 7. HNSW 코사인 인덱스 재생성
CREATE INDEX idx_chunks_hnsw ON document_chunks
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
