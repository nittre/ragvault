CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE document_chunks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_table    VARCHAR(100) NOT NULL,
    source_id       VARCHAR(100) NOT NULL,
    content         TEXT NOT NULL,
    content_hash    VARCHAR(64) NOT NULL,
    embedding       vector(768),
    access_groups   TEXT[] NOT NULL DEFAULT ARRAY['all'],
    token_count     INTEGER,
    metadata        JSONB,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE (source_table, source_id, content_hash)
);

CREATE INDEX idx_chunks_embedding ON document_chunks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX idx_chunks_source ON document_chunks (source_table, source_id);
CREATE INDEX idx_chunks_access_groups ON document_chunks USING GIN (access_groups);

CREATE TABLE api_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL,
    key_hash        VARCHAR(255) NOT NULL,
    key_prefix      VARCHAR(20) NOT NULL,
    scopes          TEXT NOT NULL DEFAULT 'api:chat',
    is_active       BOOLEAN DEFAULT true,
    expires_at      TIMESTAMP NOT NULL,
    last_used_at    TIMESTAMP,
    created_by      VARCHAR(200),
    created_at      TIMESTAMP DEFAULT NOW(),
    deactivated_at  TIMESTAMP,
    UNIQUE (key_hash)
);

CREATE INDEX idx_apikeys_prefix ON api_keys (key_prefix);
CREATE INDEX idx_apikeys_active ON api_keys (is_active, expires_at);

CREATE TABLE search_config (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_key      VARCHAR(100) NOT NULL UNIQUE,
    config_value    TEXT NOT NULL,
    description     VARCHAR(500),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE model_variants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    variant_name    VARCHAR(100) NOT NULL UNIQUE,
    model_id        VARCHAR(200) NOT NULL,
    top_k           INTEGER NOT NULL DEFAULT 5,
    threshold       DECIMAL(4,3) NOT NULL DEFAULT 0.65,
    description     VARCHAR(500),
    is_active       BOOLEAN DEFAULT true
);

CREATE TABLE audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_email      VARCHAR(200),
    action          VARCHAR(100) NOT NULL,
    resource        VARCHAR(200),
    detail          TEXT,
    ip_address      VARCHAR(50),
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_audit_user ON audit_log (user_email, created_at DESC);
CREATE INDEX idx_audit_action ON audit_log (action, created_at DESC);

-- 초기 데이터
INSERT INTO search_config (config_key, config_value, description) VALUES
    ('default_top_k', '5', 'RAG 기본 Top-K'),
    ('default_threshold', '0.65', 'RAG 기본 코사인 유사도 임계값'),
    ('max_context_tokens', '5000', '참고자료 최대 토큰'),
    ('max_history_turns', '10', '대화 이력 최대 턴 수');

INSERT INTO model_variants (variant_name, model_id, top_k, threshold, description) VALUES
    ('company-rag-precise', 'qwen2.5:7b-instruct-q4_K_M', 3, 0.75, '정밀 검색: Top-K=3, 유사도>=0.75'),
    ('company-rag-balanced', 'qwen2.5:7b-instruct-q4_K_M', 5, 0.65, '균형 검색: Top-K=5, 유사도>=0.65 (기본)'),
    ('company-rag-broad', 'qwen2.5:7b-instruct-q4_K_M', 10, 0.55, '광범위 검색: Top-K=10, 유사도>=0.55');
