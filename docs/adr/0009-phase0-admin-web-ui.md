# ADR-0009: Phase 0 Admin Web UI — 계정 발급 메일·비밀번호 재설정·임베디드 관리자 셸

- **상태**: Accepted
- **결정일**: 2026-06-30
- **결정자**: 개발팀
- **관련 ADR**: ADR-0003, ADR-0011
- **영향 받는 코드**: `app-internal/.../config/AdminSpaConfig.java`, `app-internal/.../service/MailService.java`, `app-internal/.../templates/email/account-created.html`, `app-internal/.../templates/email/password-reset.html`

## 컨텍스트 (Why)

챗 서비스 초기 단계에는 관리자가 사용자 계정을 발급하고(API Key 포함) 비밀번호 재설정을 안내할 최소한의 수단이 필요했으나, 아직 `frontend/internal` 의 정식 Vite 빌드 어드민 SPA(ADR-0003 이전 상태)가 준비되지 않은 시점이었다. 별도의 프론트엔드 빌드 파이프라인 없이도 앱을 배포 즉시 사용할 수 있는 최소 기능(Phase 0)이 필요했다.

## 결정 (What)

```
1. AdminSpaConfig — app-internal 자체에 정적 리소스 서빙 경로를 둔다.
   - /admin        → /admin/ 로 리다이렉트
   - /admin/       → classpath:/static/admin/index.html 로 forward
     (React BrowserRouter 새로고침/직접 URL 진입 대응, 빌드 도구 없는 CDN 기반
     React 셸을 얹을 것을 전제로 설계)
   - /admin/**     → classpath:/static/admin/ 정적 자산 서빙
   - /status, /status/ → classpath:/static/status/index.html (운영 상태 페이지)

2. MailService — Thymeleaf 템플릿 기반 비동기(@Async) 메일 발송.
   - 계정 발급 메일(email/account-created): API Key 는 이 메일 발송 후
     재조회 불가하므로 반드시 이 메일로만 최초 전달.
   - 비밀번호 재설정 메일(email/password-reset).
   - th:text 만 사용(th:utext 금지)해 템플릿 XSS 방지.
   - SMTP 자격증명은 환경변수(spring.mail.username/password)로만 주입.
```

## 결과 (Consequences)

### 장점
- 별도 프론트엔드 빌드·배포 파이프라인 없이도 계정 발급/비밀번호 재설정이라는 최소 운영 기능을 앱 배포 시점부터 즉시 사용할 수 있었다.
- `/status` 페이지는 이후에도 유지되어 별도 용도로 계속 쓰이고 있다.

### 단점·트레이드오프
- 이후 ADR-0003(프론트엔드 모노레포 통합)으로 `frontend/internal` 전체 SPA(Vite 빌드)가 도입되면서 실제 어드민 페이지들(`UsersPage`, `ApiKeysPage` 등)은 `frontend/internal/src/pages/admin/*` 로 이관되었다. 현재 저장소에는 `app-internal/src/main/resources/static/admin/` 디렉토리 자체가 존재하지 않아, `AdminSpaConfig` 의 `/admin/**` 핸들러는 매칭되는 정적 자산이 없는 상태로 남아 있다(`/status` 경로만 실제로 쓰임).

### 후속 작업
- `AdminSpaConfig` 의 `/admin/**` 관련 라우팅·리소스 핸들러가 더 이상 쓰이지 않는다면 제거 검토(현재 배포 위험 없이 방치되어 있을 뿐, 실 사용자 영향 없음).

## 대안 (검토했으나 채택 안 한 옵션)

### 옵션 A — Phase 0 없이 처음부터 정식 프론트엔드 SPA 구축
장기적으로는 더 깔끔하지만, 최초 배포 시점에 관리자 최소 기능(계정 발급/비밀번호 재설정)이 지연된다.
**채택 안 한 이유**: 빠른 초기 운영 개시를 우선.

## 참고

- ADR-0003 (이후 frontend/internal 정식 SPA 도입 — 실질적 관리자 UI 이관처)
- ADR-0011 (Phase 0 이후 자체 JWT 인증 체계로 전환, 기존 사용자 최초 로그인 지원과 연계)
