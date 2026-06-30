-- V9: datasource_config 에 is_internal 컬럼 추가 (Phase 3 AccessPolicy 지원)
ALTER TABLE datasource_config ADD COLUMN IF NOT EXISTS is_internal BOOLEAN NOT NULL DEFAULT FALSE;
