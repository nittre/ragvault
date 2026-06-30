-- M3: Text-to-SQL 스키마
-- sql_table_config: SQL 대상 테이블 화이트리스트
-- sql_execution_log: SQL 실행 감사 로그
-- search_config: M3 설정값 추가

CREATE TABLE sql_table_config (
    id               SERIAL PRIMARY KEY,
    source_table     VARCHAR(100) NOT NULL UNIQUE,
    display_name     VARCHAR(200),
    description      TEXT,
    allowed_columns  TEXT[],
    excluded_columns TEXT[],
    relationships    JSONB DEFAULT '{}',
    sample_queries   JSONB DEFAULT '[]',
    data_sensitivity VARCHAR(20) NOT NULL DEFAULT 'internal'
        CHECK (data_sensitivity IN ('public', 'internal', 'restricted')),
    allowed_groups   TEXT[] NOT NULL DEFAULT ARRAY['all'],
    is_active        BOOLEAN DEFAULT true,
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_sql_table_config_groups ON sql_table_config USING GIN (allowed_groups);

-- SQL 실행 감사 로그
CREATE TABLE sql_execution_log (
    id                BIGSERIAL PRIMARY KEY,
    user_email        VARCHAR(200),
    api_key_id        UUID,
    intent            VARCHAR(20),
    question          TEXT NOT NULL,
    generated_sql     TEXT,
    validation_result VARCHAR(50),
    validation_reason TEXT,
    execution_status  VARCHAR(20),
    row_count         INT,
    elapsed_ms        INT,
    error_message     TEXT,
    response_id       VARCHAR(50),
    created_at        TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_sql_log_user   ON sql_execution_log (user_email, created_at DESC);
CREATE INDEX idx_sql_log_status ON sql_execution_log (execution_status, created_at DESC);

-- search_config M3 추가 항목
INSERT INTO search_config (config_key, config_value, description) VALUES
('sql_query_timeout_sec',       '10',              'SQL 쿼리 타임아웃 (초)'),
('sql_max_rows',                '1000',            'SQL 결과 최대 행 수'),
('sql_rate_limit_per_minute',   '30',              'SQL 경로 분당 한도'),
('intent_classifier_model',     '"qwen2.5:14b"',   '의도 분류용 LLM'),
('hybrid_synthesis_timeout_sec','15',              '혼합 검색 타임아웃 (초)')
ON CONFLICT (config_key) DO NOTHING;
