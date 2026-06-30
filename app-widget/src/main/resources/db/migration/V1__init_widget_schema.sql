-- V1: widget-backend 초기 스키마
-- pgvector extension + document_chunks 테이블

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS document_chunks (
    id              BIGSERIAL PRIMARY KEY,
    source_table    VARCHAR(100)  NOT NULL,
    source_id       VARCHAR(200)  NOT NULL,
    source_type     VARCHAR(50)   NOT NULL DEFAULT 'faq',
    chunk_index     INTEGER       NOT NULL DEFAULT 0,
    content         TEXT          NOT NULL,
    content_hash    VARCHAR(64)   NOT NULL,
    token_count     INTEGER       NOT NULL DEFAULT 0,
    embedding       vector(1024),
    embedding_model VARCHAR(100)  NOT NULL DEFAULT 'bge-m3',
    tokenizer_model VARCHAR(100)  NOT NULL DEFAULT 'bge-m3',
    metadata        JSONB,
    access_groups   TEXT[]        NOT NULL DEFAULT ARRAY['all'],
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_chunk_source UNIQUE (source_table, source_id, chunk_index, embedding_model)
);

-- pgvector IVFFlat 인덱스 (코사인 거리)
CREATE INDEX IF NOT EXISTS idx_chunks_embedding
    ON document_chunks USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

-- 소스 기반 조회 인덱스
CREATE INDEX IF NOT EXISTS idx_chunks_source
    ON document_chunks (source_table, source_id);

-- access_groups GIN 인덱스 (&& 연산자용)
CREATE INDEX IF NOT EXISTS idx_chunks_access_groups
    ON document_chunks USING GIN (access_groups);
