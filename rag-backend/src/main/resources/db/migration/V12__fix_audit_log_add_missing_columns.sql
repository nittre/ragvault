-- V12: audit_log 누락 컬럼 추가
-- V2에서 기본 스키마로 audit_log를 생성한 후
-- V6(CREATE TABLE IF NOT EXISTS)가 스킵되어 컬럼이 추가되지 않은 문제 수정.

ALTER TABLE audit_log
    ADD COLUMN IF NOT EXISTS response_id     VARCHAR(100),
    ADD COLUMN IF NOT EXISTS intent          VARCHAR(50),
    ADD COLUMN IF NOT EXISTS request_summary TEXT;
