-- model_variants: Java 엔티티·리포지토리 없음, 코드에서 미참조 (dead table)
-- search_config 가 동일 역할 수행
DROP TABLE IF EXISTS model_variants;

-- sync_log: Java 엔티티·리포지토리 없음, 코드에서 미참조 (dead table)
-- sync_jobs / binlog_events 로 동기화 추적 중
DROP TABLE IF EXISTS sync_log;
