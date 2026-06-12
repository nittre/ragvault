# ADR-0011: 자체 JWT 인증 — Open WebUI 세션 교체

- **상태**: Accepted
- **결정일**: 2026-06-11
- **결정자**: 시니어 백엔드 엔지니어
- **관련 ADR**: ADR-0006 (Superseded), ADR-0009 (개정), ADR-0010 (단기 저장소)
- **영향 받는 문서**: `requirements/07-auth-security.md`, `docs/policies/openwebui-fork.md`

## 컨텍스트

Phase 0 초기 설계는 Open WebUI Fork가 인증(세션 쿠키)을 담당하고,
Spring Boot는 `AdminSessionFilter`를 통해 Open WebUI `/auth/verify` API를 호출하여 사용자를 식별하는 구조였다(ADR-0006, ADR-0009).

### 문제점

1. **Open WebUI 의존성**: Spring Boot 인증이 Open WebUI 프로세스 가용성에 종속. Open WebUI 재시작 시 모든 API 요청 인증 불가.
2. **Fork 부담 과다**: Chat UI + Admin UI + 인증 프록시 + `/auth/verify` 엔드포인트 등 변경 파일 ~20+개 예상. 사내 개발 팀이 upstream 동기화 유지 어려움.
3. **기술 스택 불일치**: Svelte 기반 UI를 사내 React/TypeScript 역량으로 유지보수 불가.
4. **경로 불일치(C1 버그)**: Open WebUI 프록시를 경유하지 않는 `/v1/chat/completions` 직접 호출 시 `X-User-Email` 헤더 누락 → 사용자 컨텍스트 없이 처리되는 잠재 버그.
5. **Admin SPA 이중 인증 복잡성**: ADR-0009 N2 결정(Open WebUI 세션 → AdminSessionFilter → `/auth/verify` 호출)이 단순성 원칙에 위배.

### 트리거

사내 팀의 Svelte 미숙 + 불필요한 Open WebUI 기능 노출 + Fork 유지보수 비용이 임계점을 초과하여 자체 프론트엔드 구축을 결정.

## 결정

**Open WebUI를 완전히 제거하고, 자체 React/Vite/TypeScript SPA + Spring Boot JWT 인증으로 교체한다.**

### 인증 흐름

```
[신규 흐름 — ADR-0011]
브라우저
  │ POST /api/v1/auth/login (email + password)
  ▼
Spring Boot JwtAuthController
  │ BCrypt 검증 → JWT 생성 (HS256, 8시간)
  │ Set-Cookie: token=<jwt>; HttpOnly; SameSite=Lax; Path=/
  ▼
브라우저 (httpOnly Cookie 자동 첨부)
  │ POST /v1/chat/completions   ← credentials: include
  │ GET  /api/v1/admin/**       ← credentials: include
  ▼
Spring Boot JwtAuthFilter (OncePerRequestFilter)
  │ 쿠키 "token" 추출 → JJWT 0.12.x 서명 검증
  │ Claims에서 email + role 추출 → SecurityContext 설정
  │ 권한: api:chat (전체) / api:admin (ADMIN+) / api:super-admin (SUPER_ADMIN)
  ▼
컨트롤러 — Authentication.getName() 으로 사용자 email 추출
```

### 신뢰 경계

```
[외부 — 불신뢰]
브라우저 ── httpOnly Cookie(JWT) ──┐
                                   ▼
[VPC 내부 — 신뢰]
Nginx (rag-frontend:18080) ── proxy_pass ──▶ Spring Boot :8080
                                              JwtAuthFilter 서명 검증 후 신뢰
```

- `TrustedHeaderFilter`는 유지 (외부 API 클라이언트의 `X-User-*` 헤더 차단)
- `ApiKeyAuthFilter`는 유지 (외부 API 클라이언트 Bearer 키 경로)

### 구성 요소

| 구성 요소 | 설명 |
|----------|------|
| `JwtService` | HS256 + JJWT 0.12.x, `${RAG_AUTH_JWT_SECRET}` 필수 env, 최소 32바이트 |
| `JwtAuthController` | `POST /api/v1/auth/login`, `/logout`, `/change-password` |
| `JwtAuthFilter` | httpOnly Cookie → JWT 파싱 → SecurityContext |
| `ApiKeyAuthFilter` | SecurityContext에 인증 정보가 이미 있으면 skip (중복 인증 방지) |
| `RagUserBootstrapRunner` | 기동 시 `password_hash NULL` 사용자 전체에 BCrypt(`BOOTSTRAP_INITIAL_PASSWORD`) 적용 |
| `V16 마이그레이션` | `rag_users`에 `password_hash`, `password_change_required` 컬럼 추가 |
| `rag-frontend` | React 18 + Vite 6 + TypeScript 5.6 + Tailwind CSS 3, Docker + Nginx |

### 환경변수

```bash
# 필수 (절대 하드코딩 금지)
RAG_AUTH_JWT_SECRET=<openssl rand -hex 32>   # 32바이트 이상
BOOTSTRAP_INITIAL_PASSWORD=<임시 비밀번호>    # 배포 후 사용자가 변경
# 유지
RAG_BACKEND_API_KEY=<...>                    # 외부 API 클라이언트용
# 제거
# WEBUI_SECRET_KEY — Open WebUI 제거로 불필요
```

### 삭제된 구성 요소

- `AdminSessionFilter.java` — Open WebUI `/auth/verify` 호출
- `static/admin/` — CDN 기반 구 어드민 SPA
- `open-webui` Docker 서비스 및 `open_webui_data` 볼륨
- `rag.admin.openwebui-verify-url` 설정 키

## 결과

### 장점
- **단일 인증 출처**: Spring Boot가 인증 전체를 소유 → Open WebUI 재시작 영향 없음
- **Fork 부담 제거**: Svelte 코드 변경 불필요, upstream 동기화 비용 0
- **사내 역량 활용**: React/TypeScript → 팀 자력 유지보수 가능
- **C1 버그 수정**: `/v1/chat/completions` 직접 호출에도 JWT로 사용자 컨텍스트 안정적 제공
- **보안 강화**: `dev-bypass: false` 고정, httpOnly Cookie → XSS 토큰 탈취 불가

### 단점·트레이드오프
- Open WebUI의 대화 관리·모델 선택 등 미사용 기능 상실 → Phase 0 범위에서는 불필요
- 비밀번호 관리 인프라 직접 소유 (BCrypt 해시, 비밀번호 변경 플로우)
- V17 마이그레이션(`conversations` 테이블)은 Phase B 후속 작업으로 연기

### 후속 작업
- `ConversationController` 구현 + V17 마이그레이션 (`conversations`, `conversation_messages`)
- 비밀번호 변경 강제 플로우 (`password_change_required: true` 사용자 → 변경 UI)
- `Jenkinsfile.webui` → `Jenkinsfile.frontend` 교체 완료
- Phase 1+: JWT 리프레시 토큰, SSE 스트리밍, OpenTelemetry 트레이싱

## 대안

### 옵션 B — Open WebUI Fork 유지 + JWT만 추가
- Open WebUI를 유지하면서 Spring Boot JWT를 추가 레이어로 도입
- 단점: Fork 부담 해결 안 됨, 인증이 두 시스템에 분산. 거부.

### 옵션 C — Keycloak / 외부 IdP 도입
- OAuth2 / OIDC 표준, 토큰 리프레시·SSO 완비
- 단점: Phase 0 단순성 원칙 위배, 추가 인프라 비용, 학습 곡선. Phase 1+ 검토.

### 옵션 D — Open WebUI Fork 유지, Admin만 자체 구현
- Chat UI는 Open WebUI 유지, Admin UI만 React로
- 단점: 인증 이원화, `/auth/verify` 의존성 유지, Fork 부담 잔존. 거부.

## 참고

- 권위 출처: `requirements/07-auth-security.md` 섹션 8·10
- 관련 결정: ADR-0006 (Superseded), ADR-0009 (개정됨)
- 구현 위치: `rag-backend/.../{JwtService,JwtAuthFilter,JwtAuthController}`, `rag-frontend/`
- 마이그레이션: `V16__add_password_hash_to_rag_users.sql`
