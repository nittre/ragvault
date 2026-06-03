/**
 * RAG 시스템 Phase 0 — k6 부하 테스트
 *
 * 시나리오: 30 VU, 5분 지속
 * 6개 경로 (RAG / SQL / HYBRID / URL / FILE / IMAGE) 랜덤 질의
 *
 * requirements/04-rag-search-strategy.md
 * requirements/08-text-to-sql.md
 * requirements/10-multimodal-files-url.md
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// ---------------------------------------------------------------------------
// 설정
// ---------------------------------------------------------------------------

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY  = __ENV.API_KEY  || 'Bearer test-api-key-for-load-test';

// Phase 0 사전 업로드된 파일 ID (실제 환경에서 교체)
const PRESET_FILE_ID = __ENV.FILE_ID || 'test-file-id-00000000-0000-0000-0000-000000000001';

// 1x1 픽셀 PNG (base64) — IMAGE 경로 최소 페이로드
const DUMMY_IMAGE_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

// ---------------------------------------------------------------------------
// Threshold
// ---------------------------------------------------------------------------

export const options = {
  vus: 30,
  duration: '5m',
  thresholds: {
    // RAG / SQL / IMAGE: p95 < 10초
    'http_req_duration{scenario:rag}':    ['p(95)<10000'],
    'http_req_duration{scenario:sql}':    ['p(95)<10000'],
    'http_req_duration{scenario:hybrid}': ['p(95)<10000'],
    'http_req_duration{scenario:image}':  ['p(95)<10000'],
    // FILE / URL: p99 < 30초 (파일 처리·외부 Fetch 포함)
    'http_req_duration{scenario:file}':   ['p(99)<30000'],
    'http_req_duration{scenario:url}':    ['p(99)<30000'],
    // 전체 에러율 1% 미만
    'http_req_failed': ['rate<0.01'],
  },
};

// ---------------------------------------------------------------------------
// 헬퍼
// ---------------------------------------------------------------------------

/**
 * 공통 요청 헤더 생성.
 * X-User-Id: Phase 0 에서는 Open WebUI 백엔드 프록시가 주입하지만
 * 부하 테스트에서는 직접 포함 (ADR-0006 참조, dev-bypass 모드).
 */
function buildHeaders(vu) {
  return {
    'Authorization':  API_KEY,
    'Content-Type':   'application/json',
    'X-User-Email':   `loadtest-user${vu}@example.com`,
    'X-User-Id':      uuidv4(),
  };
}

/**
 * POST /v1/chat/completions 공통 래퍼.
 * OpenAI 호환 인터페이스 사용.
 */
function chatRequest(messages, extraFields, tags) {
  const payload = JSON.stringify({
    model: 'rag-default',
    messages,
    stream: false,
    ...extraFields,
  });
  return http.post(
    `${BASE_URL}/v1/chat/completions`,
    payload,
    {
      headers: buildHeaders(__VU),
      tags,
      timeout: '35s',
    }
  );
}

// ---------------------------------------------------------------------------
// 시나리오별 함수
// ---------------------------------------------------------------------------

function scenarioRag() {
  const res = chatRequest(
    [{ role: 'user', content: '우리 회사 보증 정책이 어떻게 돼?' }],
    { intent_hint: 'RAG' },
    { scenario: 'rag' }
  );
  check(res, {
    'RAG 200': (r) => r.status === 200,
    'RAG choices 존재': (r) => {
      try { return JSON.parse(r.body).choices?.length > 0; } catch { return false; }
    },
  });
}

function scenarioSql() {
  const res = chatRequest(
    [{ role: 'user', content: '지난달 매출 합계 알려줘' }],
    { intent_hint: 'SQL' },
    { scenario: 'sql' }
  );
  check(res, {
    'SQL 200': (r) => r.status === 200,
    'SQL choices 존재': (r) => {
      try { return JSON.parse(r.body).choices?.length > 0; } catch { return false; }
    },
  });
}

function scenarioHybrid() {
  const res = chatRequest(
    [{ role: 'user', content: '고객 수와 상품 보증 정책 같이 알려줘' }],
    { intent_hint: 'HYBRID' },
    { scenario: 'hybrid' }
  );
  check(res, {
    'HYBRID 200': (r) => r.status === 200,
    'HYBRID choices 존재': (r) => {
      try { return JSON.parse(r.body).choices?.length > 0; } catch { return false; }
    },
  });
}

function scenarioUrl() {
  const res = chatRequest(
    [{ role: 'user', content: 'https://example.com 요약해줘' }],
    { intent_hint: 'URL_FETCH' },
    { scenario: 'url' }
  );
  // URL Fetch: 200 또는 SSRF 차단(400) 모두 허용 — example.com 은 공개 IP 이므로 200 기대
  check(res, {
    'URL 2xx 또는 4xx': (r) => r.status >= 200 && r.status < 500,
    'URL 응답 있음': (r) => r.body?.length > 0,
  });
}

function scenarioFile() {
  const res = chatRequest(
    [{ role: 'user', content: '첨부 파일 내용 요약해줘' }],
    {
      intent_hint: 'FILE',
      file_ids: [PRESET_FILE_ID],
    },
    { scenario: 'file' }
  );
  // FILE: 200 또는 파일 없으면 404
  check(res, {
    'FILE 응답 있음': (r) => r.status >= 200 && r.status < 500,
  });
}

function scenarioImage() {
  const res = chatRequest(
    [
      {
        role: 'user',
        content: [
          { type: 'text', text: '이 이미지에 무엇이 있나요?' },
          {
            type: 'image_url',
            image_url: { url: `data:image/png;base64,${DUMMY_IMAGE_BASE64}` },
          },
        ],
      },
    ],
    { intent_hint: 'IMAGE' },
    { scenario: 'image' }
  );
  check(res, {
    'IMAGE 200': (r) => r.status === 200,
    'IMAGE choices 존재': (r) => {
      try { return JSON.parse(r.body).choices?.length > 0; } catch { return false; }
    },
  });
}

// ---------------------------------------------------------------------------
// 메인 — 랜덤 경로 선택
// ---------------------------------------------------------------------------

const SCENARIOS = [scenarioRag, scenarioSql, scenarioHybrid, scenarioUrl, scenarioFile, scenarioImage];

export default function () {
  const fn = SCENARIOS[Math.floor(Math.random() * SCENARIOS.length)];
  fn();
  sleep(1); // 1초 think-time (실사용자 모사)
}
