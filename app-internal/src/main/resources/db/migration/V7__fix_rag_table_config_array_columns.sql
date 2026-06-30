-- V7: rag_table_config의 text[] 컬럼을 TEXT로 변환
--
-- 배경: RagTableConfig 엔티티는 content_columns/metadata_columns/allowed_groups를
-- 쉼표 구분 TEXT 문자열로 관리하지만 V2 스키마에서 text[] 배열로 생성됨.
-- JPA 삽입 시 "column is of type text[] but expression is of type character varying" 오류 발생.
-- → text[] → TEXT 타입으로 변경 (기존 데이터는 array_to_string으로 변환).
--
-- ADR-0002: Phase 0 단순 구조 유지 — TEXT로 충분.

ALTER TABLE rag_table_config
    ALTER COLUMN content_columns  TYPE TEXT USING array_to_string(content_columns, ','),
    ALTER COLUMN metadata_columns TYPE TEXT USING array_to_string(metadata_columns, ','),
    ALTER COLUMN allowed_groups   TYPE TEXT USING array_to_string(allowed_groups, ',');

-- content_columns NOT NULL 기본값 설정
ALTER TABLE rag_table_config
    ALTER COLUMN content_columns SET DEFAULT '';
