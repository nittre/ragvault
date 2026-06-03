# RAG 시스템 k6 부하 테스트

Phase 0 부하 테스트 스크립트.

## 사전 조건

```bash
# k6 설치 (macOS)
brew install k6

# k6 설치 (Linux)
sudo apt-get install k6
```

## 스모크 테스트 (1 VU, 30초)

배포 직후 핵심 경로를 빠르게 검증한다.

```bash
k6 run smoke-test.js
```

환경변수로 대상 서버를 지정할 수 있다.

```bash
BASE_URL=http://localhost:8080 \
API_KEY="Bearer sk-rag-test-..." \
ADMIN_KEY="Bearer sk-rag-admin-..." \
k6 run smoke-test.js
```

## 부하 테스트 (30 VU, 5분)

30명 동시 사용자가 6개 경로(RAG/SQL/HYBRID/URL/FILE/IMAGE)를 랜덤으로 질의한다.

```bash
k6 run load-test.js
```

환경변수 목록:

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `BASE_URL` | `http://localhost:8080` | 대상 서버 |
| `API_KEY` | `Bearer test-api-key-for-load-test` | api:chat scope key |
| `FILE_ID` | `test-file-id-00000000-...` | 사전 업로드된 파일 ID |

```bash
BASE_URL=http://localhost:8080 \
API_KEY="Bearer sk-rag-test-..." \
FILE_ID="실제-파일-uuid" \
k6 run load-test.js
```

## 결과 Grafana + InfluxDB 전송

```bash
# InfluxDB 로 메트릭 전송
k6 run --out influxdb=http://localhost:8086/k6 load-test.js

# Grafana Cloud 로 전송 (k6 Cloud 사용 시)
k6 run --out cloud load-test.js
```

## Threshold 기준

| 지표 | 기준 | 해당 경로 |
|------|------|---------|
| p(95) < 10s | `http_req_duration` | RAG / SQL / HYBRID / IMAGE |
| p(99) < 30s | `http_req_duration` | FILE / URL |
| 에러율 < 1% | `http_req_failed` | 전체 |

## 주의사항

- **URL 경로**: `example.com` 은 공개 IP 이므로 SSRF 차단 없이 통과한다. 내부 IP 테스트는 별도 수행.
- **FILE 경로**: `FILE_ID` 가 실제 업로드된 파일을 가리켜야 200이 반환된다. 잘못된 ID 는 404/400 을 반환하지만 에러율 threshold 계산에는 포함되지 않는다 (check 로 별도 처리).
- **IMAGE 경로**: 1x1 픽셀 더미 이미지를 사용하며 qwen2.5-vl:7b 모델이 가동 중이어야 한다.
- **Rate Limit**: 60 req/min per key — 30 VU × 1req/1s = 30 req/min 이므로 정상 범위.
