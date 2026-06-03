# 에러 처리 / 장애 대응 설계

> 운영 중 발생할 수 있는 장애를 분류하고 대응 전략 정의.

관련 문서:
- [01-architecture.md](01-architecture.md)
- [03-data-sync-pipeline.md](03-data-sync-pipeline.md)
- [04-rag-search-strategy.md](04-rag-search-strategy.md)
- [08-text-to-sql.md](08-text-to-sql.md) — SQL 생성/실행 에러 처리 (11절)

---

## 목차

1. [개요 및 결정사항](#1-개요-및-결정사항)
2. [장애 유형 분류](#2-장애-유형-분류)
3. [Retry + Backoff (Phase 0)](#3-retry--backoff-phase-0)
4. [Circuit Breaker (Phase 1+)](#4-circuit-breaker-phase-1)
5. [Fallback 응답](#5-fallback-응답)
6. [Health Check](#6-health-check)
7. [Spot 인스턴스 회수 대응](#7-spot-인스턴스-회수-대응)
8. [부분 장애 UX](#8-부분-장애-ux)
9. [알람 정책](#9-알람-정책)
10. [Runbook (복구 절차)](#10-runbook-복구-절차)
11. [데이터 무결성 (멱등성)](#11-데이터-무결성-멱등성)
12. [모니터링 메트릭](#12-모니터링-메트릭)
13. [카오스 엔지니어링 (Phase 2+)](#13-카오스-엔지니어링-phase-2)

---

## 1. 개요 및 결정사항

### 핵심 결정사항

| 항목 | Phase 0 결정 |
|------|------------|
| Circuit Breaker | Phase 1+ 도입 (MVP 단순화) |
| 사용자 응답 재시도 | 2회 (1초, 3초) |
| 배치 작업 재시도 | 3회 (1, 5, 25초) |
| 에러 응답 | 표준 메시지 + 오류 ID |
| Health Check | Liveness + Readiness + Deep (3종) |
| Spot 회수 대응 | 503 응답 + Auto Scaling 자동 복구 |
| 알람 채널 | Critical / Warning / Info 3분리 |
| 알람 억제 | 30분 중복 차단 + Rate Limit |
| Runbook | 주요 5~8개 |
| 멱등성 | content_hash UPSERT |
| 카오스 엔지니어링 | Phase 2+ |
| **시스템 상태 페이지 (UX 결정)** | `customera.ragservice.com/status` 정적 HTML. Spring Boot `/api/v1/health/deep` 기반. Fallback 카드에 링크. [user-journeys.md S10 M10-4](../docs/ux/user-journeys.md) |
| **Fallback UX (UX 결정)** | 일반 챗 버블 텍스트 (Open WebUI 기본) + 인라인 [🔄 다시 시도] 링크 + [상태 페이지] 링크. 클라이언트 자동 재시도 X. [user-journeys.md S10 M10-1/M10-3](../docs/ux/user-journeys.md) |
| **SSE 중간 단절 (UX 결정)** | 부분 답변 유지 + "응답이 중단되었습니다" 라벨 + 다시 시도 + audit partial 기록. [user-journeys.md S10 M10-5](../docs/ux/user-journeys.md) |
| **장기 장애 메시지 (UX 결정)** | 메시지 고정 + 상태 페이지로 위임. [user-journeys.md S10 M10-8](../docs/ux/user-journeys.md) |

---

## 2. 장애 유형 분류

### 컴포넌트별 시나리오

| 컴포넌트 | 장애 유형 | 빈도 | 영향 | 회복 시간 |
|---------|---------|------|------|---------|
| Ollama (LLM) | Spot 회수 | 월 1~3회 | 응답 불가 | 1~5분 |
| Ollama (LLM) | GPU OOM | 드물 | 응답 실패 | 즉시 (재시도) |
| Ollama (Embedding) | 네트워크 지연 | 가끔 | 검색 지연 | 즉시 |
| pgvector | RDS 페일오버 | 드물 | 일시 다운 | 1~2분 |
| pgvector | 디스크 풀 | 예방 가능 | 쓰기 실패 | 수동 대응 |
| 고객사 MySQL | 네트워크 끊김 | 가끔 | 동기화 실패 | 자동 재시도 |
| 고객사 MySQL | DB 다운 | 드물 | 동기화 중단 | 고객사 복구 |
| Open WebUI | Pod 재시작 | 드물 | 일시 다운 | 30초~1분 |
| Spring Boot | OOM/크래시 | 드물 | 응답 불가 | k3s 자동 재시작 |
| Redis | Pod 재시작 | 드물 | 캐시 손실 | 30초~1분 |
| k3s 노드 | 노드 다운 | 드물 | Pod 재배치 | 1~3분 |
| ALB | AWS 장애 | 매우 드물 | 외부 접근 차단 | AWS 복구 대기 |
| AZ 전체 | AZ 다운 | 매우 드물 | 컴퓨트 다운 | 수동 대응 |

### 가용성 영향 분류

```
[전체 서비스 다운]
- ALB 장애
- AZ 전체 다운

[핵심 기능 다운]
- Ollama LLM 다운 → 응답 불가
- pgvector 다운 → 검색 불가

[부분 기능 다운]
- 고객사 MySQL 끊김 → 동기화만 멈춤 (검색은 OK)
- Open WebUI Pod 1개 다운 → 다른 Pod로 처리

[사용자 무영향]
- Redis 다운 → 캐시 미스만 발생
- Prometheus/Grafana 다운 → 모니터링만 영향
```

### 자동 회복 가능성

```
[완전 자동]
- k3s Pod 재시작 (헬스체크 실패 시)
- RDS Multi-AZ 페일오버
- ALB의 비정상 노드 제외

[부분 자동]
- Spot 회수 → Auto Scaling이 새 인스턴스 생성
- 고객사 MySQL 일시 끊김 → Retry로 복구

[수동 필요]
- 디스크 풀
- 고객사 DB 영구 장애
- AZ 다운 시 다른 AZ로 수동 전환
```

---

## 3. Retry + Backoff (Phase 0)

### Spring Retry 적용

```java
@Service
public class ChunkProcessor {
    
    @Retryable(
        value = {EmbeddingException.class, DataAccessException.class},
        maxAttempts = 3,
        backoff = @Backoff(
            delay = 1000,
            multiplier = 5,
            maxDelay = 30000
        )
    )
    public void processChunk(Chunk chunk) {
        float[] embedding = ollamaClient.embed(chunk.getContent());
        chunkRepository.save(chunk.withEmbedding(embedding));
    }
    
    @Recover
    public void recover(Exception ex, Chunk chunk) {
        log.error("Chunk failed after retries", ex);
        binlogEventRepository.markFailed(chunk, ex);
        discordNotifier.alert("Chunk processing failed: " + chunk.getId());
    }
}
```

### 컴포넌트별 재시도 정책

| 컴포넌트 | 최대 시도 | 간격 | 이유 |
|---------|---------|------|------|
| Ollama LLM (사용자 응답) | **2회** | 1초, 3초 | 사용자 대기 짧게 |
| Ollama 임베딩 (배치) | 3회 | 1, 5, 25초 | 시간 여유 |
| pgvector 쿼리 | 3회 | 1, 2, 4초 | 일시 부하 흡수 |
| 고객사 MySQL 연결 | 5회 | 5, 10, 30, 60, 120초 | 네트워크 복구 대기 |
| binlog 연결 | 무한 | 30초 간격 | 동기화 안정성 |

### 재시도 안 하는 경우

```
[즉시 실패]
- 4xx 클라이언트 에러 (잘못된 요청)
- 인증 실패 (401, 403)
- 토큰 한도 초과
- 입력 유효성 검사 실패

[재시도 의미 없음]
- OutOfMemoryError
- 디스크 풀
- 인덱스 손상
```

### 사용자 응답 재시도 (2회)

```
[1차 시도] 즉시
   ↓ 실패
[2차 시도] 1초 후
   ↓ 실패
[Fallback 응답] 사용자에게 표준 에러 메시지

→ 최악 약 4초 대기 후 실패
→ 사용자 인내 한계 내
```

---

## 4. Circuit Breaker (Phase 1+)

### Resilience4j 도입 (Phase 1+)

```
[적용 시점]
- Phase 0: Spring Retry만 (단순)
- Phase 1+: Resilience4j 도입
- 이유: MVP 단계엔 과한 복잡도, 실제 트래픽 패턴 본 후 결정
```

### 상태 머신

```
CLOSED (정상)
  │ 실패율 임계값 초과
  ▼
OPEN (차단)
  │ 일정 시간 경과
  ▼
HALF_OPEN (테스트)
  ├── 성공 → CLOSED
  └── 실패 → OPEN
```

### 적용 대상 (Phase 1+)

```
[Ollama LLM 호출]
- 임계값: 50% 실패율
- 차단 시간: 30초
- Half-Open: 3 요청 테스트
- Fallback: "AI 일시 사용 불가" 응답

[Ollama 임베딩 호출]
- 임계값: 70% 실패율
- 차단 시간: 10초
- Fallback: 검색 단순화 또는 에러

[고객사 MySQL]
- 임계값: 5회 연속 실패
- 차단 시간: 5분
- Fallback: 동기화 일시 중단

[pgvector]
- Circuit Breaker 적용 안 함
- 핵심 의존성, 차단해도 답 없음
- Retry + 알람만
```

### 설정 예시 (Phase 1+ 도입 시)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      ollama-llm:
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
      ollama-embedding:
        sliding-window-size: 20
        failure-rate-threshold: 70
        wait-duration-in-open-state: 10s
```

---

## 5. Fallback 응답

### 표준 메시지 + 오류 ID

```
[Ollama LLM 다운]
"AI 응답 서비스에 일시적인 문제가 있습니다.
1~2분 후 다시 시도해주세요.
(오류 ID: err_abc123)"

[Ollama 임베딩 다운]
"검색 시스템에 일시적인 문제가 있습니다.
잠시 후 다시 시도해주세요.
(오류 ID: err_def456)"

[pgvector 다운]
"내부 자료에 접근할 수 없습니다.
관리자에게 문의해주세요.
(오류 ID: err_ghi789)"

[검색 결과 없음 — 장애 아님]
"관련된 정보를 자료에서 찾을 수 없습니다.
질문을 다른 표현으로 시도해주세요."

[Rate Limit 초과]
"사용량 한도에 도달했습니다.
N분 후 다시 시도해주세요."

[프롬프트 인젝션 의심]
"보안 정책에 위반되는 요청입니다.
회사 자료에 관한 질문만 도와드릴 수 있습니다."

[일반 에러]
"일시적인 오류가 발생했습니다.
잠시 후 다시 시도해주세요.
(오류 ID: err_xxx)"
```

### 오류 ID 정책

```
모든 응답에 고유 ID 부여:
- 정상: response_id (audit_logs와 연결)
- 에러: error_id (관리자가 추적 가능)

[형식]
err_{8자리 영숫자}
예: err_abc12345

[저장]
error_logs 테이블에 상세 정보:
- error_id
- timestamp
- user_email
- request_body
- stack_trace
- correlation_id
```

### error_logs 테이블

```sql
CREATE TABLE error_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    error_id        VARCHAR(20) UNIQUE NOT NULL,  -- 'err_abc12345'
    error_type      VARCHAR(100),
    error_message   TEXT,
    stack_trace     TEXT,
    user_email      VARCHAR(200),
    request_path    VARCHAR(500),
    request_body    JSONB,
    response_status INT,
    correlation_id  VARCHAR(100),
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_error_logs_error_id ON error_logs (error_id);
CREATE INDEX idx_error_logs_user ON error_logs (user_email, created_at DESC);
```

### Spring Boot 예외 처리

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(EmbeddingException.class)
    public ResponseEntity<ErrorResponse> handleEmbedding(EmbeddingException ex) {
        String errorId = generateErrorId();
        errorLogService.save(errorId, ex);
        
        return ResponseEntity.status(503).body(new ErrorResponse(
            errorId,
            "검색 시스템에 일시적인 문제가 있습니다."
        ));
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        String errorId = generateErrorId();
        errorLogService.save(errorId, ex);
        log.error("Unhandled error [{}]", errorId, ex);
        
        return ResponseEntity.status(500).body(new ErrorResponse(
            errorId,
            "일시적인 오류가 발생했습니다."
        ));
    }
}
```

---

## 6. Health Check

### 3종 엔드포인트

```
GET /api/v1/health        ← Liveness
GET /api/v1/health/ready  ← Readiness
GET /api/v1/health/deep   ← 상세 진단 (관리자용)
```

### Liveness — "프로세스 살아있나?"

```java
@GetMapping("/api/v1/health")
public ResponseEntity<Map<String, String>> liveness() {
    return ResponseEntity.ok(Map.of("status", "UP"));
}
```

- 항상 200 OK
- 실패 시 k3s가 Pod 재시작
- 가볍게 (DB 체크 X)

### Readiness — "트래픽 받을 준비됐나?"

```java
@GetMapping("/api/v1/health/ready")
public ResponseEntity<HealthStatus> readiness() {
    boolean dbOk = checkPgVector();
    boolean ollamaOk = checkOllama();
    
    if (dbOk && ollamaOk) {
        return ResponseEntity.ok(HealthStatus.up());
    }
    return ResponseEntity.status(503).body(HealthStatus.down());
}
```

- 의존성 체크 (DB, Ollama)
- 실패 시 ALB가 트래픽 제외
- 시작 시 의존성 준비 대기

### Deep — 관리자용 상세 진단

```json
GET /api/v1/health/deep

{
  "status": "DEGRADED",
  "timestamp": "2026-05-12T10:30:00Z",
  "components": {
    "pgvector": {
      "status": "UP",
      "latency_ms": 5,
      "details": "Connection pool: 8/10 active"
    },
    "ollama_llm": {
      "status": "UP",
      "latency_ms": 850,
      "model": "qwen2.5:14b"
    },
    "ollama_embedding": {
      "status": "UP",
      "latency_ms": 45,
      "model": "nomic-embed-text"
    },
    "customer_mysql": {
      "status": "DEGRADED",
      "binlog_lag_seconds": 3600,
      "details": "binlog 1 hour behind"
    },
    "redis": {
      "status": "UP",
      "latency_ms": 1
    }
  }
}
```

### k3s Probe 설정

```yaml
# helm/rag-backend/templates/deployment.yaml
livenessProbe:
  httpGet:
    path: /api/v1/health
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  timeoutSeconds: 3
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /api/v1/health/ready
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 2
```

---

## 7. Spot 인스턴스 회수 대응

### 회수 절차

```
[AWS Spot Interruption Notice]
- 2분 전 알림 (IMDS 메타데이터)
- http://169.254.169.254/latest/meta-data/spot/instance-action

[감지 → 대응]
1. aws-node-termination-handler 데몬이 감지 (30초 폴링)
2. k3s에 노드 unschedulable 표시
   kubectl cordon <node>
3. Ollama Pod를 drain
   kubectl drain <node> --ignore-daemonsets
4. Auto Scaling Group이 새 Spot 요청
5. 새 EC2 부팅 (AMI 사용, 30초~1분)
6. k3s 클러스터에 join
7. Ollama Pod 자동 배치
8. 서비스 재개

[다운타임]
1~3분
```

### aws-node-termination-handler

```yaml
# k3s DaemonSet으로 모든 노드에 배포
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: aws-node-termination-handler
  namespace: kube-system
spec:
  template:
    spec:
      containers:
      - name: handler
        image: amazon/aws-node-termination-handler:latest
        env:
        - name: ENABLE_SPOT_INTERRUPTION_DRAINING
          value: "true"
        - name: ENABLE_REBALANCE_DRAINING
          value: "true"
```

### Phase 0 Fallback: 503 응답

```
Spot 회수 중 사용자 요청 → 503 응답:
"AI 일시 사용 불가, 1~2분 후 다시 시도"

Discord 알람:
"⚠️ Spot 인스턴스 회수 — Auto Scaling이 새 인스턴스 생성 중"

→ 사용자 경험 일시 저하 (수용 가능)
→ 비용 우선
```

### Phase 1+ 강화 검토

```
[옵션 B] On-Demand 백업 인스턴스
- 평소 1대 OD + 1대 Spot
- Spot 회수 시 OD가 부담
- 비용 ~70% 증가

[옵션 C] 다중 Spot
- 평소 2대 Spot (서로 다른 인스턴스 타입)
- 둘 다 동시 회수 가능성 낮음
- 비용 ~2배

→ Phase 1+ 트래픽 보고 결정
```

---

## 8. 부분 장애 UX

### 시나리오별 사용자 경험

```
[시나리오 1: 동기화만 멈춤]
영향: 최신 데이터 RAG 반영 안 됨
사용자: 정상 (구 데이터로 응답)
관리자: Discord 알람 받고 대응

[시나리오 2: Ollama LLM 다운]
영향: 응답 생성 불가
사용자: "AI 응답에 일시적 문제 (오류 ID: xxx)"
관리자: Discord 알람 + 자동 복구

[시나리오 3: Ollama 임베딩 다운 (LLM 살아있음)]
영향: 새 검색 불가
사용자: "검색 시스템에 일시적 문제"
관리자: Discord 알람

[시나리오 4: pgvector 페일오버 중]
영향: 1~2분 검색 불가
사용자: "잠시 후 다시 시도"
관리자: 자동 복구 (Multi-AZ)

[시나리오 5: Redis 다운]
영향: 캐시 미스, Rate Limit 작동 안 함
사용자: 약간 느려질 뿐
관리자: k3s 자동 재시작 대기
```

### 부분 장애 알림 (Phase 1+)

```
[Open WebUI 시스템 메시지]
"⚠️ 일부 자료가 최신이 아닐 수 있습니다.
 (최근 동기화: 2시간 전)"

→ Open WebUI 포크 필요
→ Phase 1+ 검토
```

---

## 9. 알람 정책

### Discord 채널 분리

```
[#alerts-critical] — 24/7 대응
- 음성 알림 활성
- 담당자 멘션 (@oncall)
- 응답 SLA: 15분 내

[#alerts-warning] — 영업시간 대응
- 모니터링 채널
- 담당자 멘션 없음
- 영업시간 내 검토

[#alerts-info] — 참고
- 일일 리포트
- 배포 완료
- 비교적 한가한 채널
```

### 알람 분류

```
[🚨 Critical → #alerts-critical]
- Ollama LLM 5분 이상 다운
- pgvector 다운
- Spring Boot Pod 모두 다운
- 디스크 80% 이상
- API 5xx 에러율 > 10%
- 보안 사고 (인젝션 다수 발생)
- binlog 지연 4시간 초과 (binlog 보존 7일 안전 마진 진입)

[⚠️ Warning → #alerts-warning]
- Spot 인스턴스 회수
- Circuit Breaker OPEN (Phase 1+)
- 응답시간 p99 > 10초
- 동기화 실패 (3회 누적)
- binlog 지연 1시간 초과 (30분 주기 운영 기준 — 정상값은 ≤ 30분)
- 디스크 60% 이상

[ℹ️ Info → #alerts-info]
- 일일 사용량 리포트
- 배포 완료
- 백업 완료
- Spot 인스턴스 변경
- 신규 고객 온보딩
```

### 알람 억제

```
[중복 방지]
같은 알람 30분 내 1회만 발송
키: alarm_type + component + severity

[유지보수 모드]
정기 점검 중 알람 차단
관리자 API:
POST /api/v1/admin/maintenance/start
  body: { "duration_minutes": 60, "components": ["all"] }
POST /api/v1/admin/maintenance/end

[Rate Limiting]
1분에 10개 이상 → 요약본 1개로 변환
"⚠️ 다수 알람 발생 (12건). 대시보드 확인 필요."
```

### 알람 메시지 형식

```
[Critical 예시]
🚨 [고객사 A] Ollama LLM 다운

상태: DOWN
지속 시간: 5분 12초
영향: 모든 응답 불가
시각: 2026-05-12 10:30:00 KST

🔗 대시보드: https://grafana.../d/abc
📖 Runbook: https://github.com/.../runbooks/01-ollama-llm-down.md
🎯 담당자: @oncall

[Warning 예시]
⚠️ binlog 지연 감지

테이블: contracts
지연: 1시간 12분
마지막 처리: 2026-05-12 09:18:00

→ 자동 복구 시도 중. 추가 지연 시 검토 필요.
```

---

## 10. Runbook (복구 절차)

### Phase 0 작성할 Runbook (5개)

```
docs/runbooks/
├── 01-ollama-llm-down.md         (LLM 다운)
├── 02-pgvector-down.md           (벡터 DB 다운)
├── 03-customer-mysql-disconnect.md (고객사 DB 끊김)
├── 04-disk-full.md               (디스크 풀)
└── 05-spot-interruption.md       (Spot 회수)
```

### Phase 1+ 추가 (3개)

```
├── 06-binlog-lag.md              (binlog 지연)
├── 07-az-failure.md              (AZ 다운)
└── 08-rate-limit-abuse.md        (남용 대응)
```

### Runbook 표준 템플릿

```markdown
# {장애명} Runbook

## 증상
- 사용자 보고: ...
- 모니터링 신호: ...
- 알람 채널: ...

## 즉시 조치 (5분 내)
1. 영향 범위 확인
2. 임시 우회 적용 (있다면)
3. 사용자 공지 (필요 시)

## 근본 원인 분석
1. 로그 확인 위치
   - CloudWatch Logs Group: ...
   - Grafana 대시보드: ...
2. 가능한 원인 목록

## 복구 절차
1. 단계별 명령
2. 검증 방법

## 예방 조치
- 재발 방지책
- 알람 임계값 조정 등
```

### 예시: Ollama LLM 다운 Runbook

```markdown
# Ollama LLM 다운 Runbook

## 증상
- API /v1/chat/completions 5xx 에러
- Discord 🚨 Critical 알람
- p99 응답시간 급증

## 즉시 조치
1. Spot 인스턴스 회수 여부 확인:
   ```bash
   aws ec2 describe-instances --filters \
     "Name=instance-lifecycle,Values=spot" \
     "Name=instance-state-name,Values=terminated"
   ```

2. 회수 맞으면 → Auto Scaling이 새 인스턴스 생성 대기 (2~5분)

3. 회수 아니면 → GPU 노드 직접 확인:
   ```bash
   ssh ec2-user@<gpu-node-ip>
   nvidia-smi          # GPU 정상?
   docker ps           # Ollama 컨테이너 살아있나?
   ```

## 복구
1. Ollama 컨테이너 재시작:
   ```bash
   docker restart ollama
   ```

2. 모델 로드 확인:
   ```bash
   curl http://localhost:11434/api/tags
   ```

3. 응답 테스트:
   ```bash
   curl http://localhost:11434/api/generate \
     -d '{"model":"qwen2.5:14b","prompt":"test"}'
   ```

4. k3s readiness 통과 확인:
   ```bash
   kubectl get pods -n rag -o wide
   ```

## 예방
- Spot 인스턴스 다중화 (Phase 1+)
- Ollama 메모리 한도 모니터링
- GPU 온도 알람 임계값 검토
```

### Runbook 위치

```
GitHub: rag-infra 리포지토리
docs/runbooks/

Discord 알람 메시지에 직접 링크 포함:
"📖 Runbook: https://github.com/.../runbooks/01-ollama-llm-down.md"
```

---

## 11. 데이터 무결성 (멱등성)

### 멱등성 보장 — content_hash UPSERT

```sql
INSERT INTO document_chunks (
    source_table, source_id, chunk_index,
    content, content_hash, embedding,
    embedding_model
) VALUES (...)
ON CONFLICT (source_table, source_id, chunk_index, embedding_model)
DO UPDATE SET
    content = EXCLUDED.content,
    embedding = EXCLUDED.embedding,
    updated_at = NOW()
WHERE document_chunks.content_hash != EXCLUDED.content_hash;
```

### 효과

```
[중복 처리 방지]
같은 청크 N번 처리 → 결과 동일
- 첫 번째: INSERT
- 두 번째 이후: WHERE content_hash 불일치 → UPDATE 안 함

[변경 감지]
content_hash가 다르면 UPDATE
- 텍스트가 바뀐 경우에만 임베딩 재생성
- 불필요한 작업 최소화

[성능]
content_hash 인덱스로 빠른 비교
```

### 트랜잭션 처리

```
[원자적 처리 단위]
binlog 이벤트 1개 처리:
1. 청크 삭제 (UPDATE 이벤트)
2. 새 청크 저장
3. binlog_position 업데이트 ← 이건 별도 트랜잭션

→ 청크 작업은 트랜잭션 안에서
→ binlog_position은 모든 이벤트 처리 후 일괄 업데이트
```

### 실패 격리

```
[정책: 실패해도 binlog 위치 전진]
1번 이벤트 실패 → binlog_events에 기록
2번 이벤트 정상 처리
3번 이벤트 정상 처리
...
모두 끝나면 binlog_position을 마지막 처리한 위치로 전진

→ 1번 이벤트는 sync_log에 남아서 관리자가 수동 재처리
→ 같은 실패 무한 반복 방지
```

---

## 12. 모니터링 메트릭

### Spring Boot Actuator 메트릭

```
[Custom 메트릭]
rag_query_total                  (총 쿼리 수)
rag_query_errors_total           (에러 수)
rag_query_latency_seconds        (응답시간 히스토그램)
rag_chunk_processing_total       (청크 처리 수)
rag_chunk_processing_errors      (청크 실패 수)
rag_binlog_position              (현재 binlog 위치)
rag_binlog_lag_seconds           (binlog 지연)
rag_circuit_breaker_state        (회로 상태, Phase 1+)
rag_active_jobs                  (실행 중 동기화 작업)
```

### Grafana 대시보드 구성

```
[메인 대시보드]
├── 응답시간 p50/p95/p99 (실시간)
├── 에러율 (지난 5분)
├── 쿼리 처리량 (RPM)
├── Ollama GPU 사용률
├── pgvector 쿼리 시간
└── binlog 지연 시간

[장애 대시보드]
├── Critical 알람 카운트
├── Circuit Breaker 상태 (Phase 1+)
├── 디스크/메모리 사용률
├── Pod 재시작 횟수
└── 실패 이벤트 큐 길이

[비즈니스 대시보드]
├── 일일 활성 사용자
├── 일일 질의 수
├── 가장 많은 키워드 Top 10
├── 사용자 피드백 (👍/👎 비율)
└── 응답 없음 비율
```

### CloudWatch 알람 임계값

```
[Critical]
- ECS/EC2 CPU > 95% (5분 지속)
- RDS CPU > 90% (5분 지속)
- 디스크 사용률 > 80%
- ALB 5xx 에러율 > 10% (5분)

[Warning]
- ECS/EC2 CPU > 70% (10분 지속)
- RDS CPU > 70% (10분 지속)
- 디스크 사용률 > 60%
- p99 응답시간 > 10초
- GPU 메모리 > 90%
```

---

## 13. 카오스 엔지니어링 (Phase 2+)

### 의도적 장애 주입으로 검증

```
[도구 후보]
- AWS Fault Injection Service (FIS)
- LitmusChaos (k3s용)
- Chaos Mesh

[시나리오]
- 무작위 Pod 종료
- 네트워크 지연 주입
- CPU/메모리 부하 시뮬레이션
- AZ 격리 시뮬레이션
- Spot 회수 시뮬레이션

[실행 시점]
- Game Day: 분기별 1회, 영업시간 중 (팀 함께 대응)
- 자동화: 비영업시간, 주간 1회
```

### 도입 기준

```
☑ Phase 2+ 도입 — 운영 안정화 후
- 6개월 무사고 운영 후
- 모니터링/알람 체계 검증 완료
- Runbook 5개 이상 정착
```

---

## Phase별 도입 계획

### Phase 0 — MVP

```
☑ Spring Retry (재시도)
☑ 표준 Fallback 응답 + 오류 ID
☑ Health Check 3종 (Liveness/Readiness/Deep)
☑ Spot 회수 → 503 응답
☑ Discord 알람 3채널
☑ Runbook 5개
☑ content_hash UPSERT 멱등성
☑ Prometheus + Grafana 기본 대시보드
☑ CloudWatch 임계값 알람
```

### Phase 1 — 정식 출시

> **분산 트레이싱 도입** — OpenTelemetry + Grafana Tempo.
> Phase 0 의 단계별 Prometheus timer 메트릭은 통계만 제공해서 개별 요청 추적이 불가능.
> Phase 1 정식 SLA 운영 시작과 함께 trace 기반 디버깅 필요. 1% sampling + 에러/느린 요청은 100%.
> 비용 ~$7/월 추가 (Tempo Pod + S3 7일 보존).



```
☑ Resilience4j Circuit Breaker
☑ Runbook 추가 (총 8개)
☑ 부분 장애 UX 알림 (Open WebUI 포크)
☑ 자동 복구 강화 (Spot 다중화)
☑ 에러 분석 자동화 (LLM-as-Judge)
```

### Phase 2 — 확장

```
☑ 카오스 엔지니어링
☑ AZ 다운 자동 복구
☑ 멀티 리전 페일오버 (필요시)
☑ AI 기반 이상 감지
```

---

## 다음 단계

이 에러 처리 설계 기반으로:
- **C. 인증/인가** (마지막) — Open WebUI ↔ Spring Boot API Key, 관리자 권한, audit log
