-- V33: 웹 검색(WEB_SEARCH) 실행 감사 로그
-- sql_execution_log와 동일한 목적: 라우팅 경로(WEB_SEARCH 단독 / HYBRID 내부)와 무관하게
-- WebSearchService.search()가 호출될 때마다 실제 실행 여부를 정확히 기록한다.

CREATE TABLE web_search_execution_log (
    id                BIGSERIAL PRIMARY KEY,
    user_email        VARCHAR(200),
    question          TEXT NOT NULL,
    hit_count         INT,
    execution_status  VARCHAR(20),   -- 'success' | 'error'
    failure_category  VARCHAR(30),   -- 'SEARXNG_ERROR' | 'NO_RESULTS' | 'LLM_ERROR'
    error_message     TEXT,
    elapsed_ms        INT,
    response_id       VARCHAR(50),
    created_at        TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_web_search_log_user    ON web_search_execution_log (user_email, created_at DESC);
CREATE INDEX idx_web_search_log_status  ON web_search_execution_log (execution_status, created_at DESC);
CREATE INDEX idx_web_search_log_failcat ON web_search_execution_log (failure_category, created_at DESC);
