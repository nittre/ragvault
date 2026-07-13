-- conversation_logs 에 라우팅 분류(action) 컬럼 추가.
-- SQL/HYBRID 라우팅 응답이 로그에 전혀 남지 않던 문제를 함께 고치면서 도입.
-- 기존 로그는 전부 RAG 경로를 통해서만 남았으므로 기본값 'RAG' 로 백필한다.
ALTER TABLE conversation_logs
    ADD COLUMN IF NOT EXISTS action varchar(20) NOT NULL DEFAULT 'RAG';
