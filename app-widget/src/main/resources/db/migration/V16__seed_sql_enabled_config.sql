-- V16: sql_enabled 검색 설정 키 시드 (V8에서 누락되어 최초 저장 시 500 에러 발생하던 문제 보완)

INSERT INTO search_config (config_key, config_value, description)
VALUES ('sql_enabled', 'false', '위젯 채팅에서 text-to-sql 경로 허용 여부')
ON CONFLICT (config_key) DO NOTHING;
