-- V19: 멀티 데이터소스 스코프 확장
-- masking_rule에 datasource_id 추가 (null = 전역 규칙)
-- rag_table_config / sql_table_config의 source_table unique 완화 → (source_table, datasource_id) 복합 unique

-- 1. masking_rule: datasource_id 추가
ALTER TABLE masking_rule ADD COLUMN IF NOT EXISTS datasource_id INTEGER REFERENCES datasource_config(id) ON DELETE CASCADE;

-- 2. masking_rule: name 단독 unique → (name, datasource_id) 복합 unique
--    기존 constraint 제거 후 partial index로 교체
ALTER TABLE masking_rule DROP CONSTRAINT IF EXISTS masking_rule_name_key;
CREATE UNIQUE INDEX IF NOT EXISTS masking_rule_name_ds_uq ON masking_rule(name, COALESCE(datasource_id, -1));

-- 3. rag_table_config: source_table 단독 unique 완화
ALTER TABLE rag_table_config DROP CONSTRAINT IF EXISTS rag_table_config_source_table_key;
CREATE UNIQUE INDEX IF NOT EXISTS rag_table_config_source_table_ds_uq
    ON rag_table_config(source_table, COALESCE(datasource_id, -1));

-- 4. sql_table_config: source_table 단독 unique 완화
ALTER TABLE sql_table_config DROP CONSTRAINT IF EXISTS sql_table_config_source_table_key;
CREATE UNIQUE INDEX IF NOT EXISTS sql_table_config_source_table_ds_uq
    ON sql_table_config(source_table, COALESCE(datasource_id, -1));

-- 5. masking_rule 조회 index (datasource_id 기준)
CREATE INDEX IF NOT EXISTS idx_masking_rule_datasource_id ON masking_rule(datasource_id);
