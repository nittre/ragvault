-- 데이터소스 라우팅 후보 좁히기용 임베딩 (하이브리드의 임베딩 절반).
-- 데이터소스 설명 + 테이블 설명을 임베딩해, 데이터소스가 많을 때 질문과 유사한 후보만 추려
-- LLM 라우팅 프롬프트를 작게 유지한다.

CREATE TABLE routing_embedding (
    id            BIGSERIAL PRIMARY KEY,
    datasource_id INTEGER NOT NULL REFERENCES datasource_config(id) ON DELETE CASCADE,
    source_table  VARCHAR(100),    -- NULL = 데이터소스 레벨 설명
    content       TEXT NOT NULL,   -- "DS명: 설명" 또는 "테이블: 설명"
    embedding     vector(1024),
    updated_at    TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_re_embedding ON routing_embedding USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_re_datasource ON routing_embedding (datasource_id);
