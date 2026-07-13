-- ADR-0005 파라미터 튜닝 파이프라인 배선: admin_param_limits 누락 row 추가.
-- top_p/max_history_turns 는 ParameterValidator 하드코딩 폴백과 동일 범위.
-- query_timeout_sec/max_result_rows 는 지금까지 항상 10초/1000행 하드코딩으로만 동작해왔으므로
-- ParameterValidator 폴백(5~60초, 10~10000행)보다 보수적인 범위로 시작한다.
INSERT INTO admin_param_limits (param_name, min_value, max_value, guard_type, description) VALUES
    ('top_p',             0.0,  1.0,   'A', 'Top-P 범위 제한'),
    ('max_history_turns', 1,    50,    'A', '히스토리 메시지 개수 범위'),
    ('query_timeout_sec', 5,    20,    'A', 'SQL 쿼리 타임아웃(초) 범위 — 보수적 상한'),
    ('max_result_rows',   10,   2000,  'A', 'SQL 결과 최대 행수 범위 — 보수적 상한')
ON CONFLICT (param_name) DO NOTHING;
