-- 1. PII 마스킹 규칙
CREATE TABLE masking_rules (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    pattern     TEXT         NOT NULL,
    replacement VARCHAR(100) NOT NULL DEFAULT '[MASKED]',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    rule_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
-- 현재 PiiMasker.java 하드코딩 6개 규칙을 초기 데이터로 삽입:
INSERT INTO masking_rules (name, pattern, replacement, rule_order) VALUES
  ('주민등록번호', '\d{6}-[1-4]\d{6}', '[주민번호]', 1),
  ('전화번호', '01[016789]-?\d{3,4}-?\d{4}', '[전화번호]', 2),
  ('카드번호', '\d{4}[\s\-]?\d{4}[\s\-]?\d{4}[\s\-]?\d{4}', '[카드번호]', 3),
  ('이메일', '[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}', '[이메일]', 4),
  ('이름', '[가-힣]{2,4}(?:씨|님|귀하)', '[이름]', 5),
  ('주소', '[가-힣]+(?:특별시|광역시|특별자치시|도|특별자치도)\s+[가-힣]+(?:시|군|구)\s+[가-힣0-9\s]+(?:로|길|동|읍|면)\s+\d+', '[주소]', 6);

-- 2. 검색 설정 (key-value)
CREATE TABLE search_config (
    key         VARCHAR(100) PRIMARY KEY,
    value       TEXT         NOT NULL,
    description VARCHAR(200),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
INSERT INTO search_config (key, value, description) VALUES
  ('top_k', '5', 'RAG 검색 결과 최대 개수'),
  ('threshold', '0.60', '코사인 유사도 임계값 (0.0~1.0)'),
  ('no_results_response', '죄송합니다, 해당 내용은 FAQ에서 찾을 수 없습니다. 다른 표현으로 질문하시거나 고객센터에 문의해 주세요.', 'FAQ 미매칭 시 fallback 응답'),
  ('injection_blocked_response', '보안 정책에 위반되는 요청입니다. FAQ 관련 질문만 도와드릴 수 있습니다.', '프롬프트 인젝션 차단 메시지');

-- 3. 감사 로그
CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    actor_email VARCHAR(200),
    action      VARCHAR(100) NOT NULL,
    target_type VARCHAR(100),
    target_id   VARCHAR(200),
    detail      TEXT,
    ip_address  VARCHAR(50),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_created_at ON audit_logs (created_at DESC);
