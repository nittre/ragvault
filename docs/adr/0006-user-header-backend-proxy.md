# ADR-0006: 사용자 식별 헤더 — Open WebUI 백엔드 프록시에서 주입

- **상태**: Accepted
- **결정일**: 2026-05-19
- **결정자**: 시니어 백엔드 엔지니어 (사용자 옵션 A 채택)
- **관련 ADR**: ADR-0002 (데이터 격리)
- **영향 받는 문서**: `requirements/07-auth-security.md` 섹션 8·10, `requirements/09-user-parameter-tuning.md` 섹션 6

## 컨텍스트

`X-User-Email` / `X-User-Id` / `X-User-Role` 헤더의 주입 위치가 두 문서에서 다르게 명시되어 있었다.

- **07-auth-security.md 섹션 8**: "내부 IP에서만 X-User-* 헤더 신뢰. 외부 IP면 헤더 제거"
- **09-user-parameter-tuning.md 섹션 6 (이전)**: 브라우저(Svelte) JS `fetch()` 에서 `X-User-Email: currentUser.email` 직접 추가

### 문제점
브라우저는 신뢰할 수 없는 클라이언트. 사용자가 임의로 헤더를 조작 가능 → **신원 위장 + 권한 상승 취약점**:
```javascript
// 악의적 사용자
fetch('/v1/chat/completions', {
  headers: {
    'X-User-Email': 'admin@customer.com',  // 위변조
    'X-User-Role': 'admin'                  // 권한 상승
  }
})
```

또한 07 의 "외부 IP 헤더 제거" 정책이 09 의 브라우저 직접 추가와 충돌 — 브라우저는 외부 IP, 따라서 헤더가 제거되어 사용자별 기능 작동 불가.

## 결정

**Open WebUI 백엔드 프록시(Python/FastAPI)가 세션 인증 후 헤더 주입**.

### 신뢰 경계
```
[외부 — 불신뢰]
브라우저 ── 세션 쿠키만 ──┐
                          ▼
[k3s VPC 내부 — 신뢰]
Open WebUI 백엔드 ── 세션 인증 후 X-User-* 헤더 주입 ──▶
Spring Boot ── TrustedHeaderFilter:
                · 발신 IP 가 k3s VPC 내부 → 헤더 신뢰
                · 외부 IP 발신 → 헤더 제거
```

### 구현
1. Open WebUI Fork 의 **백엔드 코드 (Python/FastAPI)** 수정 — `/v1/chat/completions` 프록시 라우트에서 클라이언트 헤더 폐기 후 세션 사용자 정보로 헤더 재구성
2. **브라우저(Svelte) 코드는 X-User-* 헤더 추가 금지** — body 의 `rag_params` 만 추가
3. Spring Boot `TrustedHeaderFilter` — `X-Forwarded-For` 최우측 신뢰 IP 가 k3s VPC CIDR 안인지 검증, 외부면 `X-User-*` 제거

### 헤더 스펙
```
X-User-Email: user@customer.com   (Open WebUI 인증 사용자)
X-User-Id:    12345               (Open WebUI 내부 사용자 ID, 문자열)
X-User-Role:  admin | user | pending
```

## 결과

### 장점
- 사용자가 헤더 위변조해도 무시됨 (방어선이 Spring Boot에 있음)
- Open WebUI 인증 = 신원의 단일 출처
- audit_log / Rate Limit / 파라미터 프로필이 실제 사용자 기준 작동
- 09 문서의 사용자별 기능(프로필·대화별 override)을 Phase 0 에 유지

### 단점·트레이드오프
- Open WebUI Python 백엔드 fork 깊이 증가 (Svelte 만 fork 보다 upstream 동기화 부담 ↑)
- 프록시 한 단계 추가 → 5~20ms latency (SSE 스트리밍 응답에선 무시 가능)

### 후속 작업
- Open WebUI Fork 의 백엔드 미들웨어 구현
- Spring Boot `TrustedHeaderFilter` 구현 + `internalCidrs` 설정 (예: 10.0.0.0/16)
- 외부에서 직접 호출(curl 등) 시 X-User-* 제거 — audit_log.user_email = NULL 또는 API Key name 으로 대체
- Phase 1+ 사용자 그룹 활성화 시 이 헤더가 user_groups 매핑 키로 사용됨

## 대안

### 옵션 B — JWT 발급 (Open WebUI 가 서명한 토큰)
- 위변조 불가 (서명 검증)
- 클라이언트 직접 호출도 안전
- 단점: Phase 0 결정 "JWT 자체 발급 시스템 없음" ([requirements/01](../../requirements/01-architecture.md) 2-3)과 충돌. 키 회전·토큰 갱신 인프라 추가 필요. 거부.

### 옵션 C — Phase 0 사용자별 기능 보류
- 보안 모델 단순화, Rate Limit 은 API Key 별만
- 단점: 09 문서의 핵심 기능(파라미터 튜닝 패널) 연기, audit_log 정확도 저하. 거부.

### 옵션 D — Open WebUI Functions/Filters Plugin
- Fork 없이 Plugin 으로 처리 가능
- 단점: Plugin 시스템 안정성 검증 필요, 헤더 대신 body 필드 사용 → 표준 OpenAI API 호환성 영향. 보조 옵션.

## 참고

- 권위 출처: `requirements/07-auth-security.md` 섹션 8·10
- 관련 코드: `09-user-parameter-tuning.md` 섹션 6 — 백엔드 미들웨어 예시
- 데이터 격리와의 관계: ADR-0002 — X-User-Email 이 user_groups 매핑 키로 사용됨 (Phase 1+)
