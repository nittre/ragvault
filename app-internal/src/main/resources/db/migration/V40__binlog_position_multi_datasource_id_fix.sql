-- binlog_position.id 멀티 데이터소스 버그 수정.
--
-- V3에서 "CHECK (id = 1)"로 싱글톤 테이블로 만들었고, V17에서 datasource_id 컬럼을
-- 추가해 멀티 데이터소스를 지원하려 했지만 이 CHECK 제약이 남아있었다.
-- BinlogPosition 엔티티도 id 기본값을 1로 고정해 @GeneratedValue가 없었기 때문에,
-- 두 번째 이후 데이터소스가 처음 동기화될 때 항상 id=1로 저장을 시도했고
-- Spring Data JPA가 이를 merge()로 처리하면서 기존 id=1 행(다른 데이터소스 소유)을
-- 그대로 덮어써버리는 문제가 있었다.
--
-- id를 IDENTITY 자동 생성 컬럼으로 전환하고, CHECK(id=1) 제약을 제거해
-- 데이터소스별로 독립된 행을 가질 수 있게 한다.

ALTER TABLE binlog_position DROP CONSTRAINT IF EXISTS binlog_position_id_check;

CREATE SEQUENCE IF NOT EXISTS binlog_position_id_seq OWNED BY binlog_position.id;
SELECT setval('binlog_position_id_seq', GREATEST((SELECT COALESCE(MAX(id), 0) FROM binlog_position), 1));
ALTER TABLE binlog_position ALTER COLUMN id SET DEFAULT nextval('binlog_position_id_seq');
