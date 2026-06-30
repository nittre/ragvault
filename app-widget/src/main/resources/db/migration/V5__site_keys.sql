CREATE TABLE site_keys (
    id             BIGSERIAL PRIMARY KEY,
    site_key       VARCHAR(100) NOT NULL UNIQUE,
    label          VARCHAR(200) NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    brand_color    VARCHAR(20)  NOT NULL DEFAULT '#2563eb',
    bot_name       VARCHAR(100) NOT NULL DEFAULT '챗봇',
    greeting       TEXT         NOT NULL DEFAULT '안녕하세요! 자주 묻는 질문이나 안내가 필요한 내용을 입력해 주세요.',
    logo_url       VARCHAR(500),
    allowed_origins TEXT,      -- 콤마 구분, null이면 WebConfig의 전체 허용 목록 사용
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_site_keys_key ON site_keys (site_key);

-- 기존 테스트용 site-key 초기 데이터
INSERT INTO site_keys (site_key, label, brand_color, bot_name, greeting) VALUES
  ('pk_test_widget_dev', '개발 테스트', '#2563eb', '데모 챗봇',
   '안녕하세요! 자주 묻는 질문이나 안내가 필요한 내용을 입력해 주세요.');
