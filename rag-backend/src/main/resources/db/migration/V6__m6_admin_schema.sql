-- M6 Admin Web UI 지원 스키마
-- search_config: 검색 파라미터 중앙 관리
-- admin_param_limits: Guard A/B 파라미터 한도 (ADR-0005)
-- audit_log: 시스템 감사 로그 (종합)

CREATE TABLE IF NOT EXISTS search_config (
    id           BIGSERIAL PRIMARY KEY,
    config_key   VARCHAR(100) NOT NULL UNIQUE,
    config_value TEXT         NOT NULL,
    description  TEXT,
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMP DEFAULT NOW()
);

INSERT INTO search_config (config_key, config_value, description) VALUES
    ('default_top_k',      '5',    '기본 Top-K 검색 문서 수'),
    ('default_threshold',  '0.65', '기본 유사도 임계값'),
    ('default_temperature','0.3',  '기본 LLM temperature'),
    ('max_tokens',         '2000', '최대 응답 토큰'),
    ('hybrid_alpha',       '0.5',  'Hybrid 검색 가중치 (0=키워드, 1=벡터)'),
    ('reranking_enabled',  'false','재랭킹 활성화 여부'),
    ('context_window',     '4000', 'LLM 컨텍스트 창 크기 (토큰)')
ON CONFLICT (config_key) DO NOTHING;

-- admin_param_limits: ADR-0005 Guard A (클램핑) / Guard B (강제 고정)
CREATE TABLE IF NOT EXISTS admin_param_limits (
    id           BIGSERIAL PRIMARY KEY,
    param_name   VARCHAR(100) NOT NULL UNIQUE,
    min_value    NUMERIC,
    max_value    NUMERIC,
    fixed_value  NUMERIC,           -- NULL 이면 Guard A (범위), 값 있으면 Guard B (고정)
    guard_type   VARCHAR(1) NOT NULL DEFAULT 'A' CHECK (guard_type IN ('A','B')),
    description  TEXT,
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMP DEFAULT NOW()
);

INSERT INTO admin_param_limits (param_name, min_value, max_value, guard_type, description) VALUES
    ('top_k',      1,    20,   'A', 'Top-K 범위 제한'),
    ('threshold',  0.0,  1.0,  'A', '유사도 임계값 범위'),
    ('temperature',0.0,  2.0,  'A', 'Temperature 범위'),
    ('max_tokens', 256,  8192, 'A', '최대 토큰 범위')
ON CONFLICT (param_name) DO NOTHING;

-- audit_log: 시스템 감사 로그 (종합)
-- requestSummary 는 PII 없음 — 첫 50자만 저장 (ADR-0008)
CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGSERIAL PRIMARY KEY,
    response_id     VARCHAR(100),       -- ResponseRawStorageService 키 (ADR-0010)
    user_email      VARCHAR(200),
    action          VARCHAR(100) NOT NULL,  -- 'CHAT', 'FILE_UPLOAD', 'SQL_QUERY', 'ADMIN_*'
    intent          VARCHAR(50),
    request_summary TEXT,               -- 최대 50자, PII 없음
    ip_address      VARCHAR(50),
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_user_email   ON audit_log(user_email);
CREATE INDEX IF NOT EXISTS idx_audit_log_action       ON audit_log(action);
CREATE INDEX IF NOT EXISTS idx_audit_log_created_at   ON audit_log(created_at);
