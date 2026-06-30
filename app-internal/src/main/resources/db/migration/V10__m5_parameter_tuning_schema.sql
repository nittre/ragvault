-- M5-1 파라미터 튜닝 데이터 레이어 (ADR-0005 7단계 우선순위)
-- 1. admin_param_limits 에 locked_reason 컬럼 추가
--    NOTE: is_locked 컬럼은 추가하지 않음. guard_type='B' 가 잠금 지표.
ALTER TABLE admin_param_limits ADD COLUMN IF NOT EXISTS locked_reason TEXT;

-- 2. Guard B 잠금 파라미터 3개 UPSERT
--    sql_temperature: fixed_value=0.1, guard_type='B'
--    sql_few_shot_examples: fixed_value=5, guard_type='B'
--    max_context_tokens: fixed_value=5000, guard_type='B'
INSERT INTO admin_param_limits (param_name, fixed_value, guard_type, description, locked_reason)
VALUES
    ('sql_temperature',        0.1,    'B', 'SQL 생성 일관성을 위해 고정',         '정확한 SQL 생성을 위해 고정'),
    ('sql_few_shot_examples',  5,      'B', 'SQL Few-Shot 예시 개수 고정',         '최적화된 값으로 고정'),
    ('max_context_tokens',     5000,   'B', '검색 결과 토큰 한도 — 시스템 자동 관리', '시스템 자동 관리')
ON CONFLICT (param_name) DO UPDATE
    SET fixed_value   = EXCLUDED.fixed_value,
        guard_type    = EXCLUDED.guard_type,
        description   = EXCLUDED.description,
        locked_reason = EXCLUDED.locked_reason,
        updated_at    = NOW();

-- 3. user_param_profiles 테이블 생성
CREATE TABLE IF NOT EXISTS user_param_profiles (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_email  VARCHAR(200) NOT NULL UNIQUE,
    params      JSONB        NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_user_profiles_email ON user_param_profiles(user_email);

-- 4. conversation_param_overrides 테이블 생성
CREATE TABLE IF NOT EXISTS conversation_param_overrides (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id  VARCHAR(200) NOT NULL,
    user_email       VARCHAR(200) NOT NULL,
    params           JSONB        NOT NULL DEFAULT '{}',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, user_email)
);

CREATE INDEX IF NOT EXISTS idx_conv_overrides_conv ON conversation_param_overrides(conversation_id, user_email);
