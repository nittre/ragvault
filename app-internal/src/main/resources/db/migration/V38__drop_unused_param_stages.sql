-- ADR-0005 파라미터 우선순위 체인 단순화: Stage2(search_config)/Stage4(user_param_profiles)/
-- Stage5(conversation_param_overrides)를 전부 제거하고 Stage1(admin_param_limits, 전역 설정)과
-- Stage2(요청 body, 세션 한정 사용자 설정)만 남긴다.
--
-- 1. Stage2(search_config)가 그동안 Stage1(admin_param_limits)을 덮어써서 실제로 적용되던 값을
--    Stage1로 이관한다. 이관하지 않고 바로 DROP하면 similarity_threshold가 0.55→0.65,
--    temperature가 0.3→0.7로 조용히 되돌아가 "/rag가 web으로 폴백되는" 원래 버그가 재발한다.
--    updated_by/updated_at도 함께 채워 감사 추적이 끊기지 않게 한다.
UPDATE admin_param_limits SET default_value = (SELECT config_value FROM search_config WHERE config_key = 'default_threshold'),
    updated_by = 'flyway_v38_migration', updated_at = CURRENT_TIMESTAMP
WHERE param_name = 'similarity_threshold' AND EXISTS (SELECT 1 FROM search_config WHERE config_key = 'default_threshold');

UPDATE admin_param_limits SET default_value = (SELECT config_value FROM search_config WHERE config_key = 'default_temperature'),
    updated_by = 'flyway_v38_migration', updated_at = CURRENT_TIMESTAMP
WHERE param_name = 'temperature' AND EXISTS (SELECT 1 FROM search_config WHERE config_key = 'default_temperature');

UPDATE admin_param_limits SET default_value = (SELECT config_value FROM search_config WHERE config_key = 'default_top_k'),
    updated_by = 'flyway_v38_migration', updated_at = CURRENT_TIMESTAMP
WHERE param_name = 'top_k' AND EXISTS (SELECT 1 FROM search_config WHERE config_key = 'default_top_k');

UPDATE admin_param_limits SET default_value = (SELECT config_value FROM search_config WHERE config_key = 'max_tokens'),
    updated_by = 'flyway_v38_migration', updated_at = CURRENT_TIMESTAMP
WHERE param_name = 'max_tokens' AND EXISTS (SELECT 1 FROM search_config WHERE config_key = 'max_tokens');

-- 2. 이관 완료 후 더 이상 쓰이지 않는 테이블 정리.
--    search_config: app-internal 전용 테이블(app-widget은 별개 DB에 동명 테이블을 별도로 가짐).
--    user_param_profiles/conversation_param_overrides: 프론트엔드와 연결된 적이 없던 죽은 기능.
DROP TABLE IF EXISTS search_config;
DROP TABLE IF EXISTS user_param_profiles;
DROP TABLE IF EXISTS conversation_param_overrides;
