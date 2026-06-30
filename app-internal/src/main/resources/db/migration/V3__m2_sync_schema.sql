-- M2: document_chunks 재생성 (개발 환경, 데이터 없음)
-- M1 인덱스 제거
DROP INDEX IF EXISTS idx_chunks_embedding;
DROP INDEX IF EXISTS idx_chunks_source;
DROP INDEX IF EXISTS idx_chunks_access_groups;
DROP TABLE IF EXISTS document_chunks;

-- M2 스펙 재생성
CREATE TABLE document_chunks (
    id               BIGSERIAL PRIMARY KEY,
    source_table     VARCHAR(100) NOT NULL,
    source_id        VARCHAR(200) NOT NULL,
    source_type      VARCHAR(50)  NOT NULL,
    chunk_index      INT          NOT NULL DEFAULT 0,
    content          TEXT         NOT NULL,
    content_hash     VARCHAR(64)  NOT NULL,
    token_count      INT          NOT NULL DEFAULT 0,
    embedding        vector(768),
    embedding_model  VARCHAR(100) NOT NULL DEFAULT 'nomic-embed-text',
    tokenizer_model  VARCHAR(100) NOT NULL DEFAULT 'nomic-embed-text',
    metadata         JSONB,
    access_groups    TEXT[]       NOT NULL DEFAULT ARRAY['all'],
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW(),
    UNIQUE (source_table, source_id, chunk_index, embedding_model)
);

CREATE INDEX idx_chunks_hnsw ON document_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_chunks_source ON document_chunks (source_table, source_id);
CREATE INDEX idx_chunks_access_groups ON document_chunks USING GIN (access_groups);

-- RAG 대상 테이블 동적 관리
CREATE TABLE rag_table_config (
    id                  SERIAL PRIMARY KEY,
    source_table        VARCHAR(100) NOT NULL UNIQUE,
    source_type         VARCHAR(50)  NOT NULL,
    chunking_strategy   VARCHAR(50)  NOT NULL DEFAULT 'recursive',
    chunk_size          INT          NOT NULL DEFAULT 500,
    chunk_overlap       INT          NOT NULL DEFAULT 50,
    title_column        VARCHAR(100),
    content_columns     TEXT[]       NOT NULL,
    metadata_columns    TEXT[],
    pk_column           VARCHAR(100) NOT NULL DEFAULT 'id',
    pii_masking_level   VARCHAR(20)  NOT NULL DEFAULT 'standard',
    data_sensitivity    VARCHAR(20)  NOT NULL DEFAULT 'internal'
        CHECK (data_sensitivity IN ('public', 'internal', 'restricted')),
    allowed_groups      TEXT[]       NOT NULL DEFAULT ARRAY['all'],
    is_active           BOOLEAN DEFAULT true,
    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW()
);

-- binlog 위치 추적 (싱글톤 row, GTID 전용)
CREATE TABLE binlog_position (
    id            INT PRIMARY KEY CHECK (id = 1),
    gtid_set      TEXT NOT NULL DEFAULT '',
    last_event_at TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT NOW()
);
INSERT INTO binlog_position (id, gtid_set) VALUES (1, '');

-- binlog 이벤트 (실패 추적 + 재처리)
CREATE TABLE binlog_events (
    id              BIGSERIAL PRIMARY KEY,
    event_type      VARCHAR(50),
    table_name      VARCHAR(100),
    source_id       VARCHAR(200),
    row_count       INT,
    processed       BOOLEAN DEFAULT false,
    attempt         INT DEFAULT 0,
    error_message   TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    processed_at    TIMESTAMP
);
CREATE INDEX idx_binlog_events_unprocessed ON binlog_events (processed, created_at) WHERE NOT processed;

-- DDL 이벤트 (하이브리드 처리)
CREATE TABLE ddl_events (
    id              BIGSERIAL PRIMARY KEY,
    sql_query       TEXT NOT NULL,
    table_name      VARCHAR(100),
    event_type      VARCHAR(50),
    risk_level      VARCHAR(20),
    auto_apply_at   TIMESTAMP,
    processed_at    TIMESTAMP,
    processed_by    VARCHAR(200),
    action_taken    VARCHAR(50),
    notes           TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_ddl_pending ON ddl_events (processed_at) WHERE processed_at IS NULL;

-- 동기화 작업 단위
CREATE TABLE sync_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trigger_type    VARCHAR(20) NOT NULL,
    triggered_by    VARCHAR(200),
    started_at      TIMESTAMP DEFAULT NOW(),
    completed_at    TIMESTAMP,
    status          VARCHAR(20) NOT NULL DEFAULT 'running',
    records_total   INT DEFAULT 0,
    records_success INT DEFAULT 0,
    records_failed  INT DEFAULT 0,
    error_message   TEXT
);

-- 동기화 상세 로그
CREATE TABLE sync_log (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id       UUID REFERENCES sync_jobs(id),
    source_table VARCHAR(100) NOT NULL,
    source_id    VARCHAR(200),
    operation    VARCHAR(20)  NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    attempt      INT DEFAULT 0,
    error_message TEXT,
    created_at   TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_sync_log_job ON sync_log (job_id);
