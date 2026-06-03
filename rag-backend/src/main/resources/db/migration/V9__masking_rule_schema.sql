-- M6 Admin: PII 마스킹 규칙 DB 관리 (ADR-0007 / ADR-0008)
-- 기존 PiiMasker 하드코딩 규칙을 DB 로 이관하여 Admin UI 에서 ON/OFF·패턴·치환토큰 관리.
-- level: standard(기본) / aggressive(추가). enabled=false 면 적용 안 함.
-- sort_order: 적용 순서 (긴 패턴 먼저 — 오탐 방지).

CREATE TABLE IF NOT EXISTS masking_rule (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,   -- 규칙 식별명 (예: 주민등록번호)
    pattern     TEXT         NOT NULL,          -- Java 정규식
    replacement VARCHAR(100) NOT NULL,          -- 치환 토큰 (예: [주민번호])
    level       VARCHAR(20)  NOT NULL DEFAULT 'standard' CHECK (level IN ('standard','aggressive')),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INTEGER      NOT NULL DEFAULT 100,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

-- 기존 PiiMasker STANDARD_RULES / AGGRESSIVE_RULES 시드.
-- 이메일은 운영 결정(2026-05)으로 enabled=false (학생/수강생 이메일 원본 노출).
INSERT INTO masking_rule (name, pattern, replacement, level, enabled, sort_order) VALUES
    ('주민등록번호', '\d{6}-[1-4]\d{6}',                                      '[주민번호]',   'standard',   TRUE,  10),
    ('전화번호',     '01[016789]-?\d{3,4}-?\d{4}',                            '[전화번호]',   'standard',   TRUE,  20),
    ('이메일',       '[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}',     '[이메일]',     'standard',   FALSE, 30),
    ('카드번호',     '\d{4}[\s\-]?\d{4}[\s\-]?\d{4}[\s\-]?\d{4}',             '[카드번호]',   'standard',   TRUE,  40),
    ('사번',         'EMP\d{4,6}',                                            '[사번]',       'standard',   TRUE,  50),
    ('계좌번호',     '\d{3,6}-?\d{2,6}-?\d{4,8}',                             '[계좌번호]',   'aggressive', TRUE,  60),
    ('사업자번호',   '\d{3}-\d{2}-\d{5}',                                     '[사업자번호]', 'aggressive', TRUE,  70)
ON CONFLICT (name) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_masking_rule_enabled ON masking_rule(enabled);
