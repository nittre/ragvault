-- SQL 테이블 LLM 민감도 분석 상태 컬럼 추가
ALTER TABLE sql_table_config
    ADD COLUMN llm_status VARCHAR(20) NOT NULL DEFAULT 'done';
