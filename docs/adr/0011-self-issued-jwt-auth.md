# ADR-0011: 자체 발급 JWT 인증으로 전환 (Open WebUI 세션 제거)

- **상태**: Accepted
- **결정일**: 2026-06-30
- **결정자**: 개발팀
- **관련 ADR**: ADR-0006, ADR-0009
- **영향 받는 코드**: `app-internal/.../config/SecurityConfig.java`, `app-internal/.../filter/JwtAuthFilter.java`, `app-internal/.../filter/ApiKeyAuthFilter.java`, `app-internal/.../controller/JwtAuthController.java`, `app-internal/.../runner/RagUserBootstrapRunner.java`, `core/.../service/JwtService.java`, `core/.../service/RagUserService.java`

## 컨텍스트 (Why)

챗 서비스는 이전에 외부 제품 Open WebUI 의 세션 기반 인증(코드 주석상 `AdminSessionFilter`)에 얹혀 있었다. Open WebUI 를 제거하고 자체 프론트엔드(`frontend/internal`)로 전환하면서, 역할 기반(RagRole: USER/ADMIN/SUPER_ADMIN)의 세분화된 권한(`api:chat`/`api:admin`/`api:super-admin`)을 자체적으로 발급·검증할 인증 체계가 필요해졌다. 또한 Open WebUI 시절부터 존재하던 기존 사용자들은 `password_hash` 가 없는 상태였기 때문에, 전환 후에도 첫 로그인을 지원할 방법이 필요했다.

## 결정 (What)

```
1. JwtAuthController (/api/v1/auth/**, SecurityConfig 에서 permitAll — 단 change-password 제외)
   - POST /login  — 이메일 + BCrypt 비밀번호 검증 → httpOnly, SameSite=Lax
     쿠키("rag-token")로 JWT 발급.
   - POST /logout — 쿠키 만료(maxAge=0).
   - POST /change-password — 인증 필요.

2. JwtAuthFilter — httpOnly 쿠키에서 JWT 추출·검증 → SecurityContext 설정.
   RagRole 에 따라 권한 매핑(모든 인증 사용자: api:chat, ADMIN 이상: api:admin,
   SUPER_ADMIN: api:admin + api:super-admin). 로그인/로그아웃/헬스체크만 필터 제외.

3. SecurityConfig 필터 순서: TrustedHeaderFilter(order=1, ADR-0006, 외부의
   X-User-* 헤더 직접 주입 차단) → JwtAuthFilter → ApiKeyAuthFilter
   (JWT 인증에 성공하면 ApiKeyAuthFilter 는 skip).

4. RagUserBootstrapRunner — 앱 기동 시:
   - BOOTSTRAP_SUPER_ADMIN_EMAIL 로 SUPER_ADMIN 계정을 없으면 생성.
   - BOOTSTRAP_INITIAL_PASSWORD 로 password_hash 가 null 인 모든 기존 사용자
     (Open WebUI 시절 사용자 포함)에게 BCrypt 해시를 일괄 설정하고
     password_change_required=true 로 표시해 최초 로그인 시 변경을 강제.
```

## 결과 (Consequences)

### 장점
- 외부 제품(Open WebUI) 의존을 제거하고 인증·세션을 완전히 자체 관리한다.
- 역할 기반 권한(`api:chat`/`api:admin`/`api:super-admin`)을 JWT claim에서 바로 도출해 세분화된 인가가 가능해졌다.
- 기존 사용자도 서비스 중단 없이 자연스럽게 새 인증 체계로 이전됐다.

### 단점·트레이드오프
- httpOnly 쿠키 기반 인증은 CSRF 고려가 필요하다 — 현재는 `SameSite=Lax` + REST API 전용(폼 기반 제출 없음) + `csrf().disable()`(stateless 세션이라 세션 고정 공격면 자체가 없음)로 대응하고 있으나, 브라우저 기반 공격 벡터는 별도로 계속 점검해야 한다.
- 위젯 서비스(`app-widget`)도 동일한 이름("token")의 쿠키를 사용해, 같은 브라우저에서 챗과 위젯 어드민에 동시 로그인 시 쿠키가 서로 덮어써 로그인이 풀리는 문제가 있었다(커밋 `616ef47`에서 챗은 `rag-token`, 위젯은 `widget-token` 으로 쿠키 이름을 분리해 해결).

### 후속 작업
- (해당 없음 — 쿠키 이름 충돌은 `616ef47`에서 이미 수정 완료)

## 대안 (검토했으나 채택 안 한 옵션)

### 옵션 A — Open WebUI 세션을 계속 프록시
전환 비용은 없지만 외부 제품 의존이 계속되고, `api:admin`/`api:super-admin` 같은 세분화된 역할 기반 권한을 Open WebUI 세션만으로 표현하기 어렵다.
**채택 안 한 이유**: 자체 프론트엔드 전환과 세분화된 관리자 권한 체계가 동시에 필요했다.

### 옵션 B — 서버 상태 저장 세션(HttpSession) 기반 인증
구현은 익숙하지만 `SessionCreationPolicy.STATELESS` 방침 및 다중 인스턴스 확장성과 상충한다.
**채택 안 한 이유**: 무상태(stateless) 인증이 배포 확장성 측면에서 더 유리하다고 판단.

## 참고

- ADR-0006 (TrustedHeaderFilter, 신뢰 프록시 CIDR)
- ADR-0009 (Phase 0 Admin UI 및 기존 사용자 최초 로그인 지원과의 연계)
