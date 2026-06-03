# ADR-0010: PII 마스킹 실패 진단을 위한 원본 응답 Short-lived Storage

- **상태**: Accepted
- **결정일**: 2026-05-21
- **결정자**: 시니어 백엔드 + UX 리서처 합동 점검 (N3) → 사용자 결정
- **관련 ADR**: ADR-0007 (SQL Layer 1+3) · ADR-0008 (모든 LLM 응답 PII 마스킹)
- **영향 받는 문서**: `requirements/07-auth-security.md` 섹션 9, `requirements/06-error-handling.md`, `docs/policies/security-and-guardrails.md`

## 컨텍스트

ADR-0007 / ADR-0008 결정: 모든 LLM 응답 경로에 Layer 3 PII 마스킹 적용. audit_log 에는 마스킹된 텍스트만 저장 (응답 본문 자체는 저장 X — 07 섹션 9).

### 문제 — 마스킹 실패 시 진단 불가

```
[시나리오]
1. LLM 이 응답 생성 (예: "홍길동(010-1234-5678)의 안건...")
2. PiiMasker 정규식이 전화번호 일부만 매칭 → "홍길동(010-1234-XXXX)..." 부분 마스킹
   또는 PiiMasker 가 케이스를 놓침 → 사용자에게 그대로 발송
3. 사용자: "내 정보가 답변에 노출됐어요!"
4. admin: 그 응답이 무엇이었는지 확인하려고 audit_log 조회
5. audit_log 에는 마스킹된 응답만 (또는 응답 본문 없음) → 진단 불가
6. CloudWatch Logs (A10-2): 마스킹 후 텍스트 → 원본 PII 추적 불가
7. → 마스킹 실패 케이스를 재현·진단·개선 불가능
```

### 추가 위험
- 사용자 신고 → admin 이 적절한 대응 못 함 → 신뢰 손실
- PiiMasker 정규식·NER 규칙 개선 데이터 부재
- Phase 1+ Llama Guard·Presidio 도입 시 어떤 케이스가 빠지는지 모름

## 결정

**원본 응답 (마스킹 전)을 별도 secure storage 에 short-lived TTL 보존**.

### 구체 사양

```yaml
[저장소]
- Backend: Redis (이미 사용 중 — ShedLock·시맨틱 캐시·Rate Limit)
- Key:     "resp_raw:{response_id}"
- Value:   원본 LLM 응답 텍스트 + 메타 (intent·user_email·llm_model·timestamp)
- TTL:     30분
- 암호화:  Redis 기본 (전송 TLS 활성) + at-rest 암호화 (Elasticache 사용 시)

[저장 시점]
- 모든 LLM 자연어화 응답 직후 (PiiMasker.mask() 호출 전)
- 모든 6개 경로 (RAG/SQL/HYBRID/URL/FILE/IMAGE)

[조회]
- admin Web UI `/admin/audit-logs/{response_id}/raw` (관리자 권한 + audit:incident-response scope)
- 30분 안 신고에만 가능 (TTL 만료 후 영구 손실 — PII 위험 최소화)
- 조회 자체가 audit_log 에 기록 ("admin X 가 response_id Y 의 원본 조회")

[접근 통제]
- Scope 'api:audit' 만으로 부족 → 신규 scope 'api:incident-response' 추가
- 일반 admin 도 신청 후 시간제 권한 (Phase 1+ 강화)
```

### 코드 의사 흐름
```java
String rawResponse = chatClient.prompt()...call().content();

// 1. 원본 short-lived storage 저장
String responseId = generateResponseId();
redisTemplate.opsForValue().set(
    "resp_raw:" + responseId,
    Map.of(
        "response", rawResponse,
        "intent", intent.name(),
        "user_email", user.email,
        "model", llmModel,
        "ts", Instant.now()
    ),
    Duration.ofMinutes(30)
);

// 2. PII 마스킹 (ADR-0008)
String masked = piiMasker.mask(rawResponse, STANDARD);
if (!masked.equals(rawResponse)) {
    metrics.increment("rag_pii_masked_total", Tag.of("path", intent.name()));
}

// 3. audit_log 에 response_id 만 저장 (원본은 Redis)
auditLogger.log(user, "rag_query", maskedQuestion, responseId);

// 4. 사용자에게 마스킹된 응답 반환
return masked;
```

## 결과

### 장점
- PII 마스킹 실패 케이스 진단 가능 (30분 안 신고 시)
- PiiMasker 정규식·Phase 1+ NER 개선 데이터 확보
- 사용자 신고 → admin 빠른 대응 (15분 SLA 와 정합)
- TTL 30분 → PII 위험 최소화

### 단점·트레이드오프
- Redis 메모리 사용 ↑ (일일 6,000 응답 × 평균 1KB = ~6MB / 30분 ≈ ~200KB 상시. 무시 가능)
- Redis 자체 침해 시 PII 노출 위험 (그러나 30분 안만)
- 30분 넘는 신고는 진단 불가 — 운영 정책: "사용자 신고는 즉시 (UI 의 🚩 버튼)"
- scope 'api:incident-response' 추가 → 권한 관리 복잡도 ↑

### 후속 작업
- `requirements/07-auth-security.md` 섹션 5 Scope 목록에 `api:incident-response` 추가
- `requirements/07-auth-security.md` 섹션 9 "응답 본문 미저장" 정책에 short-lived 예외 명시
- admin Web UI 의 `/admin/audit-logs` 화면에 "원본 조회" 버튼 (30분 TTL 안만 활성)
- 사용자 화면 (S9 답변 액션)에 "🚩 신고" 클릭 시 response_id 자동 전송 → admin 즉시 알림
- `spec-check` 스킬에 회귀 검증 추가:
  ```bash
  # 모든 LLM 응답 후 Redis 저장 + 마스킹 적용 확인
  grep -rn "chatClient.prompt\|chatClient.call" rag-backend/src/main/java/ \
       | grep -v "responseRawStorage.save\|piiMasker.mask"
  ```

## 대안

### 옵션 B — CloudWatch Logs (A10-2) 만
이전 결정. 마스킹 후 텍스트만 → 마스킹 실패 케이스 진단 불가. 거부.

### 옵션 C — audit_log 에 원본 저장
ADR-0008 의 "응답 본문 미저장" 정책 일부 폐기. PII 위험 큼. 거부.

### 옵션 D — 답변 재실행 (재현)
LLM 비결정성으로 재현 어려움. 거부.

### TTL 길이 — 30분 vs 1시간 vs 24시간
30분 = 사용자 즉시 신고 가정. UI 의 🚩 버튼이 빠르면 충분.
1시간 = 사용자 일과 중 발견 → 신고. 메모리 부담 ↑.
24시간 = 너무 김. PII 위험 ↑.

→ **30분 채택** + Phase 1+ 운영 데이터 본 후 조정.

## 참고

- 권위 출처: 이 ADR + `docs/ux/user-journeys.md` S2 M2-7 (사용자 신고)
- 관련 ADR: ADR-0007, ADR-0008
- 관련 메트릭: `rag_pii_masked_total{path}` (ADR-0008) + 신규 `rag_response_raw_access_total` (admin 조회 추적)
