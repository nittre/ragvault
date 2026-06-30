-- 항목 4: 비즈니스 규칙 동적 검색 (아임웹 query_knowledge_base 패턴)
-- business_rules 텍스트를 행 단위로 임베딩해 질문 관련 규칙만 동적 주입한다.
-- [고정] 마커가 붙은 규칙(is_fixed=true)은 항상 주입, 나머지는 임베딩 top-k 검색.
-- 임베딩 차원 1024 = bge-m3 (document_chunks 와 동일).

CREATE TABLE sql_business_rule_chunk (
    id                  BIGSERIAL PRIMARY KEY,
    sql_table_config_id INTEGER NOT NULL REFERENCES sql_table_config(id) ON DELETE CASCADE,
    source_table        VARCHAR(100) NOT NULL,
    rule_text           TEXT NOT NULL,
    is_fixed            BOOLEAN NOT NULL DEFAULT false,
    embedding           vector(1024),
    created_at          TIMESTAMP DEFAULT NOW()
);

-- 코사인 검색 인덱스 (V8 document_chunks 패턴 동일)
CREATE INDEX idx_brule_embedding ON sql_business_rule_chunk
    USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_brule_config ON sql_business_rule_chunk (sql_table_config_id);
