-- V11: search_config 테이블에 updated_by 컬럼 추가
-- V2에서 생성된 search_config 테이블에 updated_by 가 누락되어
-- V6의 CREATE TABLE IF NOT EXISTS 가 스킵됨 → 컬럼 미생성.
-- SearchConfig 엔티티의 updatedBy 필드와 동기화.

ALTER TABLE search_config
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(200);
