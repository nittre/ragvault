-- P3: 비즈니스 규칙 컬럼 추가 (아임웹 기법 — DB로 LLM 동작 제어)
-- 코드 배포 없이 계산식·필터 규칙 변경 가능
ALTER TABLE sql_table_config ADD COLUMN IF NOT EXISTS business_rules TEXT;

COMMENT ON COLUMN sql_table_config.business_rules IS
    'LLM 프롬프트에 주입할 비즈니스 도메인 규칙. 예: 매출=amount-cancel_amount, status=''completed'' 필터 등';
