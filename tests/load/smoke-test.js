/**
 * RAG 시스템 Phase 0 — k6 스모크 테스트
 *
 * 목적: 배포 직후 핵심 경로가 살아있는지 빠르게 확인
 * 설정: 1 VU, 30초
 *
 * 검사 항목:
 * 1. GET  /api/v1/health/deep         → 200
 * 2. POST /v1/chat/completions (RAG)  → 200
 * 3. GET  /api/v1/admin/search-config → 200 (admin 헤더 포함)
 */

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL   = __ENV.BASE_URL   || 'http://localhost:8080';
const API_KEY    = __ENV.API_KEY    || 'Bearer test-api-key-for-load-test';
const ADMIN_KEY  = __ENV.ADMIN_KEY  || 'Bearer test-admin-api-key-for-smoke';

export const options = {
  vus: 1,
  duration: '30s',
  thresholds: {
    'http_req_duration': ['p(95)<5000'],
    'http_req_failed':   ['rate<0.01'],
    // 체크 실패율 0
    'checks': ['rate==1.0'],
  },
};

// ---------------------------------------------------------------------------
// 1. Health Deep Check
// ---------------------------------------------------------------------------

function checkHealth() {
  const res = http.get(`${BASE_URL}/api/v1/health/deep`, {
    tags: { name: 'health_deep' },
    timeout: '10s',
  });
  check(res, {
    'health/deep 200': (r) => r.status === 200,
    'health/deep status UP': (r) => {
      try { return JSON.parse(r.body).status === 'UP'; } catch { return false; }
    },
  });
}

// ---------------------------------------------------------------------------
// 2. RAG Chat Completions
// ---------------------------------------------------------------------------

function checkRagChat() {
  const payload = JSON.stringify({
    model: 'rag-default',
    messages: [{ role: 'user', content: '안녕하세요. 테스트 질의입니다.' }],
    stream: false,
  });
  const res = http.post(`${BASE_URL}/v1/chat/completions`, payload, {
    headers: {
      'Authorization': API_KEY,
      'Content-Type':  'application/json',
      'X-User-Email':  'smoke-test@example.com',
      'X-User-Id':     '00000000-0000-0000-0000-000000000001',
    },
    tags: { name: 'rag_chat' },
    timeout: '30s',
  });
  check(res, {
    'chat/completions 200': (r) => r.status === 200,
    'chat/completions choices 존재': (r) => {
      try { return JSON.parse(r.body).choices?.length > 0; } catch { return false; }
    },
  });
}

// ---------------------------------------------------------------------------
// 3. Admin Search Config (api:admin scope 필요)
// ---------------------------------------------------------------------------

function checkAdminSearchConfig() {
  const res = http.get(`${BASE_URL}/api/v1/admin/search-config`, {
    headers: {
      'Authorization': ADMIN_KEY,
      'X-User-Email':  'admin@example.com',
      'X-User-Id':     '00000000-0000-0000-0000-000000000002',
    },
    tags: { name: 'admin_search_config' },
    timeout: '10s',
  });
  check(res, {
    'admin/search-config 200': (r) => r.status === 200,
    'admin/search-config 응답 있음': (r) => r.body?.length > 0,
  });
}

// ---------------------------------------------------------------------------
// 메인
// ---------------------------------------------------------------------------

export default function () {
  checkHealth();
  sleep(0.5);

  checkRagChat();
  sleep(0.5);

  checkAdminSearchConfig();
  sleep(1);
}
