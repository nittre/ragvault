# ADR-0010: LLM 원본 응답 단기 저장소 — PII 마스킹 실패 진단

- **상태**: Accepted
- **결정일**: 2026-06-30
- **결정자**: 개발팀
- **관련 ADR**: ADR-0008, ADR-0011
- **영향 받는 코드**: `app-internal/.../service/ResponseRawStorageService.java`, `app-internal/.../config/SecurityConfig.java`(`api:incident-response` 권한), `app-internal/.../controller/AdminRawResponseController.java`, `app-internal/.../controller/AdminAuditLogController.java`

## 컨텍스트 (Why)

ADR-0008 의 `PiiMasker` 는 정규식 기반이라 오탐(과잉 마스킹)뿐 아니라 미탐(마스킹 누락)의 가능성도 있다. 마스킹 실패가 실제로 발생했는지 사후 진단하려면 마스킹 전 원본 LLM 응답이 필요하다. 그러나 원본을 무기한 보관하면 PII 노출 표면을 오히려 넓히는 모순이 생기므로, "진단은 가능하되 노출 창은 최소화"하는 절충이 필요했다.

## 결정 (What)

```
1. 모든 LLM 응답 경로(RAG/SQL/HYBRID)는 piiMasker.mask() 호출 직전에
   ResponseRawStorageService.store(rawResponse, intent, userEmail, llmModel)
   를 호출해 마스킹되지 않은 원본을 Redis 에 저장하고 responseId 를 받는다.
2. TTL = 30분. key 형식은 resp_raw:{16자 UUID hex}.
3. 조회(retrieve)는 관리자 화면에서 api:incident-response 권한 보유자만
   가능하도록 SecurityConfig 에서 강제한다(GET /api/v1/admin/audit-logs/*/raw).
4. store() 는 mask() 호출 전에 실행되어야 한다는 순서 불변식은 각 서비스
   구현부의 규율(discipline)로 지켜지며, 별도의 코드 레벨 강제 장치는 없다.
```

## 결과 (Consequences)

### 장점
- PII 마스킹 실패가 의심되는 사고 대응 상황에서만, 짧은 시간 동안 원본을 조회해 실제로 마스킹이 빠졌는지 진단할 수 있다.
- TTL 30분으로 원본이 영구 보관되지 않아 노출 창을 최소화한다.
- 조회 권한을 `api:incident-response` 로 분리해 일반 관리자도 원본을 상시 열람할 수 없게 한다.

### 단점·트레이드오프
- "mask() 호출 전에 store() 를 먼저 호출해야 한다"는 순서 불변식이 각 서비스 구현에 개별적으로 위임되어 있어, 정적 분석이나 컴파일 타임 강제가 없다 — 신규/변경 경로에서 순서가 뒤집혀도 잡히지 않는다.
- Redis 장애 시 store() 가 예외를 삼키고 로그만 남기므로(원본 저장 실패), 그 사이 발생한 마스킹 실패는 사후 진단이 불가능해진다.

### 후속 작업
- 응답 경로별로 `store()` → `mask()` 순서가 실제로 지켜지고 있는지 전수 점검(진행 중 — 별도 계획 참고).
- 가능하면 두 호출을 하나의 헬퍼로 묶어 순서를 코드 레벨에서 강제하는 리팩터링 검토.

## 대안 (검토했으나 채택 안 한 옵션)

### 옵션 A — 원본을 저장하지 않고 마스킹 실패는 로그 샘플링으로만 대응
구현이 단순하지만, 마스킹 실패가 의심되는 특정 사고 건에 대해 정확한 원본을 확인할 방법이 없다.
**채택 안 한 이유**: 사고 대응 시 "정확히 무엇이 새어나갔는지" 확인 가능해야 한다는 요구를 충족하지 못함.

### 옵션 B — 원본을 DB에 영구 저장(감사 목적)
장기 추적은 가능하나, PII 노출 표면을 항구적으로 넓히는 것이라 ADR-0008 의 취지와 정면으로 충돌한다.
**채택 안 한 이유**: TTL 기반 단기 저장으로 진단 목적은 충분히 달성 가능하다고 판단.

## 참고

- ADR-0008 (PII 마스킹 원칙)
- ADR-0011 (`api:incident-response` 등 권한 체계의 기반이 되는 JWT 인증)
