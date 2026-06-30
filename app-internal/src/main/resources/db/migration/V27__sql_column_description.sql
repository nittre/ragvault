-- 컬럼 자연어 설명 저장. 테이블 설명은 sql_table_config.description(기존 컬럼) 사용.
-- COMMENT 우선, 없으면 LLM 자동 생성, 어드민 수정 가능. SQL 생성 프롬프트에 인라인 주입.
-- source: 'comment'(DB 코멘트) | 'llm'(자동 생성) | 'human'(어드민 수정)

CREATE TABLE sql_column_description (
    id            BIGSERIAL PRIMARY KEY,
    datasource_id INTEGER NOT NULL REFERENCES datasource_config(id) ON DELETE CASCADE,
    source_table  VARCHAR(100) NOT NULL,
    column_name   VARCHAR(100) NOT NULL,
    description   TEXT NOT NULL,
    source        VARCHAR(20) NOT NULL DEFAULT 'llm',
    updated_at    TIMESTAMP DEFAULT NOW(),
    UNIQUE (datasource_id, source_table, column_name)
);

CREATE INDEX idx_scd_table ON sql_column_description (datasource_id, source_table);
