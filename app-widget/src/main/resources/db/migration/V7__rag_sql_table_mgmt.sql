-- ============================================================================
-- 데이터소스별 RAG 테이블 풀 관리 + SQL 테이블 관리(text-to-sql) 스키마
--
-- rag-practice(rag-backend)의 다음 마이그레이션을 ragvault 컨벤션
-- (TIMESTAMPTZ, BOOLEAN NOT NULL DEFAULT, pgvector vector(1024))으로 통합 이식:
--   - rag_table_config       (RAG 대상 테이블 컬럼/청킹 설정)
--   - sql_table_config       (SQL 경로 화이트리스트)
--   - sql_column_description (컬럼 자연어 설명, SQL 프롬프트 인라인 주입)
--   - routing_embedding      (데이터소스/테이블 설명 임베딩 — 라우팅 후보 좁히기)
--   - sql_execution_log      (text-to-sql 실행 감사 로그)
--
-- 멀티 데이터소스 전제: 단독 source_table UNIQUE 대신
-- (datasource_id, source_table) 복합 UNIQUE.
-- ============================================================================

-- ── RAG 테이블 설정 ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS rag_table_config (
    id                SERIAL PRIMARY KEY,
    datasource_id     INTEGER      NOT NULL REFERENCES datasource_config(id) ON DELETE CASCADE,
    source_table      VARCHAR(100) NOT NULL,
    source_type       VARCHAR(50)  NOT NULL DEFAULT 'mysql',
    chunking_strategy VARCHAR(50)  NOT NULL DEFAULT 'recursive',
    chunk_size        INTEGER      NOT NULL DEFAULT 500,
    chunk_overlap     INTEGER      NOT NULL DEFAULT 50,
    title_column      VARCHAR(100),
    content_columns   TEXT,
    metadata_columns  TEXT,
    pk_column         VARCHAR(100) NOT NULL DEFAULT 'id',
    pii_masking_level VARCHAR(20)  DEFAULT 'standard',
    data_sensitivity  VARCHAR(20)  NOT NULL DEFAULT 'internal',
    llm_status        VARCHAR(20)  DEFAULT 'done',
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (datasource_id, source_table)
);
CREATE INDEX IF NOT EXISTS idx_rtc_datasource ON rag_table_config (datasource_id);

-- ── SQL 테이블 화이트리스트 ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sql_table_config (
    id               SERIAL PRIMARY KEY,
    datasource_id    INTEGER      NOT NULL REFERENCES datasource_config(id) ON DELETE CASCADE,
    source_table     VARCHAR(100) NOT NULL,
    display_name     VARCHAR(200),
    description      TEXT,
    allowed_columns  TEXT[],
    excluded_columns TEXT[],
    relationships    JSONB,
    sample_queries   JSONB,
    data_sensitivity VARCHAR(20)  NOT NULL DEFAULT 'internal',
    allowed_groups   TEXT[]       NOT NULL DEFAULT ARRAY['all'],
    llm_status       VARCHAR(20)  DEFAULT 'done',
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (datasource_id, source_table)
);
CREATE INDEX IF NOT EXISTS idx_stc_datasource ON sql_table_config (datasource_id);
CREATE INDEX IF NOT EXISTS idx_stc_active ON sql_table_config (datasource_id, is_active);

-- ── 컬럼 자연어 설명 ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sql_column_description (
    id            BIGSERIAL PRIMARY KEY,
    datasource_id INTEGER      NOT NULL REFERENCES datasource_config(id) ON DELETE CASCADE,
    source_table  VARCHAR(100) NOT NULL,
    column_name   VARCHAR(100) NOT NULL,
    description   TEXT         NOT NULL,
    source        VARCHAR(20)  NOT NULL DEFAULT 'llm',  -- comment | llm | human
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (datasource_id, source_table, column_name)
);
CREATE INDEX IF NOT EXISTS idx_scd_table ON sql_column_description (datasource_id, source_table);

-- ── 라우팅 임베딩 (데이터소스/테이블 설명) ──────────────────────────────────
CREATE TABLE IF NOT EXISTS routing_embedding (
    id            BIGSERIAL PRIMARY KEY,
    datasource_id INTEGER      NOT NULL REFERENCES datasource_config(id) ON DELETE CASCADE,
    source_table  VARCHAR(100),               -- NULL = 데이터소스 레벨 설명
    content       TEXT         NOT NULL,       -- "DS명: 설명" 또는 "테이블: 설명"
    embedding     vector(1024),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_re_embedding ON routing_embedding USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_re_datasource ON routing_embedding (datasource_id);

-- ── SQL 실행 감사 로그 ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sql_execution_log (
    id                BIGSERIAL PRIMARY KEY,
    user_email        VARCHAR(200),
    api_key_id        UUID,
    intent            VARCHAR(20),
    question          TEXT        NOT NULL,
    generated_sql     TEXT,
    validation_result VARCHAR(50),             -- allowed | denied
    validation_reason TEXT,
    execution_status  VARCHAR(20),             -- success | timeout | error
    row_count         INTEGER,
    elapsed_ms        INTEGER,
    error_message     TEXT,
    response_id       VARCHAR(50),
    failure_category  VARCHAR(30),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sel_created ON sql_execution_log (created_at DESC);
