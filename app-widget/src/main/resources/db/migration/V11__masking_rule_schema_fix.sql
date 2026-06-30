-- V11: masking_rules 테이블을 MaskingRule 엔티티와 동기화
-- 1) 테이블명 변경: masking_rules → masking_rule
-- 2) 컬럼명 변경: rule_order → sort_order
-- 3) 누락 컬럼 추가: datasource_id, level

ALTER TABLE masking_rules RENAME TO masking_rule;
ALTER TABLE masking_rule RENAME COLUMN rule_order TO sort_order;

ALTER TABLE masking_rule
    ADD COLUMN IF NOT EXISTS datasource_id INTEGER,
    ADD COLUMN IF NOT EXISTS level VARCHAR(20) NOT NULL DEFAULT 'standard';
