-- ADR-0005: admin_param_limits에 default_value(Stage 1 기본값) 컬럼 추가.
-- 서버 코드(HardcodedDefaults.java)에 하드코딩됐던 13개 파라미터 기본값을 전부 이 컬럼으로 이관한다.
-- 앞으로 Stage 1 기본값은 오직 이 컬럼에서만 나오며, 서버 코드에는 폴백값을 두지 않는다.
ALTER TABLE admin_param_limits ADD COLUMN IF NOT EXISTS default_value TEXT;

UPDATE admin_param_limits SET default_value = '5'    WHERE param_name = 'top_k';
UPDATE admin_param_limits SET default_value = '0.65' WHERE param_name = 'similarity_threshold';
UPDATE admin_param_limits SET default_value = '0.7'  WHERE param_name = 'temperature';
UPDATE admin_param_limits SET default_value = '2000' WHERE param_name = 'max_tokens';
UPDATE admin_param_limits SET default_value = '0.1'  WHERE param_name = 'sql_temperature';
UPDATE admin_param_limits SET default_value = '5'    WHERE param_name = 'sql_few_shot_examples';
UPDATE admin_param_limits SET default_value = '5000' WHERE param_name = 'max_context_tokens';
UPDATE admin_param_limits SET default_value = '0.9'  WHERE param_name = 'top_p';
UPDATE admin_param_limits SET default_value = '10'   WHERE param_name = 'max_history_turns';
UPDATE admin_param_limits SET default_value = '10'   WHERE param_name = 'query_timeout_sec';
UPDATE admin_param_limits SET default_value = '1000' WHERE param_name = 'max_result_rows';

-- force_path/hybrid_synthesis_style: enum 파라미터라 min/max/fixed_value(NUMERIC) 개념이 없다.
-- default_value(TEXT)만 사용하고, guard_type='A'로 두되 min/max가 없어 Guard A 클램핑은 적용되지 않는다
-- (ParameterResolver.applyGuardA()가 Number 타입만 클램핑하므로 문자열은 자동으로 건너뜀).
INSERT INTO admin_param_limits (param_name, guard_type, description, default_value) VALUES
    ('force_path',             'A', '라우팅 강제 모드 (AUTO/FORCE_RAG/FORCE_SQL/FORCE_HYBRID)', 'AUTO'),
    ('hybrid_synthesis_style', 'A', '하이브리드 종합 스타일 (BALANCED/SQL_FIRST/RAG_FIRST)',    'BALANCED')
ON CONFLICT (param_name) DO NOTHING;
