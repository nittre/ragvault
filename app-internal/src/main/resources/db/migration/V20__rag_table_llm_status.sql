-- RAG 테이블 LLM 분석 상태 컬럼 추가
-- bulk import 시 즉시 등록 후 백그라운드 LLM 분석 결과 반영 여부 추적
ALTER TABLE rag_table_config
    ADD COLUMN llm_status VARCHAR(20) NOT NULL DEFAULT 'done';
