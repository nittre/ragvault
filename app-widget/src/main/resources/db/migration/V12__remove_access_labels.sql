-- V12: 비활성(dead) 접근제어 라벨 제거
-- - document_chunks.access_groups : 모든 청크가 ARRAY['all'] 로만 저장되어 필터가 no-op 이었음 (AccessPolicy 제거와 함께 폐기)
-- - datasource_config.is_internal : true 로 세팅되는 경로가 없어 항상 false (WidgetAccessPolicy 게이트가 실효 없음)
-- 위젯의 사내 데이터 격리는 프로세스·DB 분리(widget_db) + 테이블/컬럼 통제가 집행한다.

DROP INDEX IF EXISTS idx_chunks_access_groups;
ALTER TABLE document_chunks   DROP COLUMN IF EXISTS access_groups;
ALTER TABLE datasource_config DROP COLUMN IF EXISTS is_internal;
