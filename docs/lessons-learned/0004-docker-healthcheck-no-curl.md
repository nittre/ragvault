# LL-0004: Docker healthcheck — 최소 이미지에 curl 없음

## 메타데이터

| 항목 | 값 |
|------|----|
| 발생일 | 2026-05-21 |
| 카테고리 | `infra` |
| 심각도 | MEDIUM |
| 관련 작업 | M0 docker-compose.dev.yml |
| 관련 ADR | 없음 |

---

## 에러 상황

`docker compose up` 실행 시 ollama, rag-backend 컨테이너가 `unhealthy` 상태:

```
OCI runtime exec failed: exec: "curl": executable file not found in $PATH
```

- `ollama/ollama:latest` — Go 바이너리 최소 이미지, curl 없음
- `eclipse-temurin:21-jre-alpine` — Alpine 최소 이미지, curl 없음
- `open-webui` — Python 이미지, curl 있음

---

## 해결

| 이미지 | 사용 도구 |
|--------|-----------|
| `ollama/ollama` | `ollama list` (ollama CLI 자체 헬스체크) |
| `eclipse-temurin:*-alpine` | `wget -q -O /dev/null <url>` (busybox wget 내장) |

---

## 비슷한 작업에 적용할 규칙

| When | Do |
|------|----|
| docker-compose healthcheck 작성 시 | 이미지별 curl/wget 가용 여부 확인 후 도구 선택 |
| Alpine 기반 이미지 (`-alpine` suffix) | `wget` 사용 (busybox 내장) |
| Go 바이너리 이미지 (ollama, etcd 등) | CLI 자체 명령 사용 (e.g. `ollama list`) |
| `curl` 강제 사용 필요 시 | Dockerfile에 `RUN apk add --no-cache curl` 추가 |
| curl/wget 모두 없는 이미지 | `nc -z localhost {port}` TCP 포트 체크 사용 |
