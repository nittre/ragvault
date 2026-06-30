-- V8: search_config 스키마를 UUID id + config_key/config_value 구조로 교체
-- V4에서 생성된 key/value 기반 구조를 SearchConfig 엔티티와 일치시킴

DROP TABLE IF EXISTS search_config;

CREATE TABLE search_config (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    config_key  VARCHAR(100) NOT NULL UNIQUE,
    config_value TEXT        NOT NULL,
    description VARCHAR(200),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

INSERT INTO search_config (config_key, config_value, description) VALUES
  ('top_k',                    '5',     'RAG 검색 결과 최대 개수'),
  ('threshold',                '0.60',  '코사인 유사도 임계값 (0.0~1.0)'),
  ('no_results_response',
   '죄송합니다, 해당 내용은 FAQ에서 찾을 수 없습니다. 다른 표현으로 질문하시거나 고객센터에 문의해 주세요.',
   'FAQ 미매칭 시 fallback 응답'),
  ('injection_blocked_response',
   '보안 정책에 위반되는 요청입니다. FAQ 관련 질문만 도와드릴 수 있습니다.',
   '프롬프트 인젝션 차단 메시지');
