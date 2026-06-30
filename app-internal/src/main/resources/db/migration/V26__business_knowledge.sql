-- 백과사전(비즈니스 규칙) 데이터소스 단위 분리.
-- 기존 sql_business_rule_chunk(테이블 종속) 제거 + sql_table_config.business_rules 제거.
-- 규칙은 여러 테이블 JOIN·집계 등 단일 테이블로 표현 불가한 경우가 많아 datasource 단위로 재정의한다.
-- 기존 데이터 리셋 허용(Phase 0).

DROP TABLE IF EXISTS sql_business_rule_chunk;

ALTER TABLE sql_table_config DROP COLUMN IF EXISTS business_rules;

CREATE TABLE business_knowledge (
    id            BIGSERIAL PRIMARY KEY,
    datasource_id INTEGER NOT NULL REFERENCES datasource_config(id) ON DELETE CASCADE,
    title         VARCHAR(200),                     -- 선택 라벨 (UI 식별용)
    rule_text     TEXT NOT NULL,
    is_fixed      BOOLEAN NOT NULL DEFAULT false,   -- [고정] 마커: 항상 주입
    embedding     vector(1024),                     -- bge-m3, document_chunks 와 동일 차원
    created_at    TIMESTAMP DEFAULT NOW(),
    updated_at    TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_bk_embedding ON business_knowledge USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_bk_datasource ON business_knowledge (datasource_id);
