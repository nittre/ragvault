# Phase 0 보안 체크리스트 결과

- **검토일**: 2026-06-01
- **검토자**: backend-engineer (자동 코드 리뷰)
- **기준 문서**: `requirements/07-auth-security.md`, ADR-0002, ADR-0006, ADR-0007, ADR-0008
- **검토 대상**:
  - `filter/ApiKeyAuthFilter.java`
  - `filter/TrustedHeaderFilter.java`
  - `security/SsrfGuard.java`
  - `security/PiiMasker.java`
  - `service/SqlValidator.java`
  - `filter/AdminSessionFilter.java`
  - `config/SecurityConfig.java`

---

## 체크리스트

| # | 항목 | 상태 | 비고 |
|---|------|------|------|
| 1 | API Key bcrypt 검증 | ✅ | `ApiKeyAuthFilter` L103: `passwordEncoder.matches(rawKey, k.getKeyHash())` — `BCryptPasswordEncoder` 사용 확인. key_prefix(15자) DB 후보 조회 후 BCrypt 검증하는 2단계 구조. |
| 2 | Rate Limit 구현 | ✅ | Redis increment/expire 패턴으로 분(60)/시(1000)/일(10000) 3개 창 구현. Redis 장애 시 fail-open (L175 주석 명시). fail-open 정책은 운영 수용 여부 별도 확인 필요. |
| 3 | TrustedHeaderFilter — 외부 X-User-* 헤더 제거 | ⚠️ | `x-user-groups`, `x-user-role`은 항상 제거. `x-user-email`은 devBypass=true + `/api/v1/user/**` 경로에서 통과 허용. **Phase 0 TODO: 내부 프록시 IP 화이트리스트 미구현** (L29 주석 확인). 외부 IP 차단은 네트워크 계층(Security Group)에 의존하는 상태. |
| 4 | SSRF Guard — private IP 차단 | ✅ | `SsrfGuard.java`: RFC 1918 전체 (10.x, 172.16–31.x, 192.168.x), loopback (127.x, ::1), link-local 169.254.x (AWS metadata), IPv6 ULA (fc00::/7) 차단 구현. DNS resolve 후 검증으로 DNS rebinding 방어. 리다이렉트 hop별 재검증은 `UrlFetchService`에서 별도 확인 필요. |
| 5 | SQL Validator — SELECT * 차단 | ✅ | `SqlValidator.java` L80–L131: JSqlParser AST 기반 `AllColumns`/`AllTableColumns` 검사. 중첩 서브쿼리 재귀 검사 포함. |
| 6 | SQL Validator — DDL/DML 차단 | ✅ | L75: `!(statement instanceof Select)` 조건으로 SELECT 외 모든 구문 거부. DROP, INSERT, UPDATE, DELETE, CREATE 등 DDL/DML 전체 차단. 테이블 화이트리스트(sql_table_config.is_active=true) + excluded_columns 추가 검증. |
| 7 | PII 마스킹 정규식 8개 | ⚠️ | `PiiMasker.java` DEFAULT_RULES fallback에 **6개** 패턴 확인: 주민번호, 전화번호, 카드번호, 사번, 계좌번호, 사업자번호. **이름(성명), 이메일, 주소 패턴은 DEFAULT_RULES에 부재**. DB `masking_rule` 테이블에 존재할 수 있으나 코드 레벨에서 보장되지 않음. 이메일은 L43 주석으로 "운영 결정(2026-05)으로 기본 비활성"임을 명시. 이름/주소 패턴 DB 입력 여부 별도 확인 필요. |
| 8 | AdminSessionFilter — role 검증 | ✅ | Open WebUI `/auth/verify` 호출 후 `is_admin` Boolean 검증. Redis 60초 캐시로 매 요청 외부 호출 방지. 검증 성공 시 `api:admin` 권한 부여. devBypass=true + `X-Dev-Admin: true` 헤더로 우회 가능하나 운영 프로파일에서는 devBypass=false로 비활성화됨을 주석 명시. |
| 9 | Scope 검증 | ✅ | `ApiKeyAuthFilter` L114: `/v1/chat/**` → `api:chat` 검증. `SecurityConfig` L108–L110: `/api/v1/admin/audit-logs/*/raw` → `api:incident-response`, `/api/v1/admin/**` → `api:admin` hasAuthority 검증. 3개 scope 모두 구현 확인. |
| 10 | 보안 헤더 (SecurityConfig) | ❌ | `SecurityConfig.java`에 X-Frame-Options, Content-Security-Policy, X-Content-Type-Options, Strict-Transport-Security 등 HTTP 보안 헤더 설정 부재. CSRF는 stateless API로 disable 처리(정상). 보안 헤더는 Spring Security의 `headers()` DSL 또는 ALB/nginx 레이어에서 추가 필요. |

---

## 발견된 이슈

### BLOCKER

없음.

### WARNING (개선 권고)

#### W-1: 보안 헤더 미적용 (항목 10)
- **파일**: `SecurityConfig.java`
- **현황**: X-Frame-Options, Content-Security-Policy, X-Content-Type-Options 헤더 미설정.
- **위험**: Admin SPA(`/admin/**`)가 클릭재킹, XSS 등에 노출 가능.
- **권고**: `http.headers(h -> h.frameOptions(...).contentTypeOptions(...).contentSecurityPolicy(...))` 추가 또는 ALB response header policy 적용.

#### W-2: PII 마스킹 이름/주소 패턴 DB 의존 (항목 7)
- **파일**: `PiiMasker.java` DEFAULT_RULES
- **현황**: DEFAULT_RULES fallback에 이름/주소 정규식 없음. DB 비어있거나 조회 실패 시 이름·주소 마스킹 누락.
- **위험**: DB 장애 또는 신규 배포 시 PII 누설 가능성 (LL-0001 참조).
- **권고**: DEFAULT_RULES에 최소한의 이름/주소 fallback 패턴 추가 또는 DB 규칙 존재 여부를 startup validation에서 확인.

#### W-3: TrustedHeaderFilter IP 화이트리스트 미구현 (항목 3)
- **파일**: `TrustedHeaderFilter.java` L29 TODO
- **현황**: Phase 0에서 내부 프록시 IP 기반 화이트리스트 미구현. Security Group로 외부 접근을 네트워크 레이어에서 차단하는 구조에 의존.
- **위험**: NetworkPolicy 설정 오류 시 외부에서 X-User-Email 직접 주입 가능.
- **권고**: Phase 1+ 전환 시 IP 화이트리스트 구현 필수. 현재 Security Group 설정 infra-engineer 검토 요청.

#### W-4: Rate Limit fail-open 정책 (항목 2)
- **파일**: `ApiKeyAuthFilter.java` L175
- **현황**: Redis 장애 시 rate limit 검사를 통과(fail-open).
- **위험**: Redis 장애 중 대량 요청이 백엔드에 직접 도달 가능.
- **권고**: 운영팀과 fail-open/fail-closed 정책 합의 후 ADR 기록 권장. fail-closed 선택 시 503 응답 추가.

---

## 결론

- **통과**: 7/10 항목
- **경고**: 2/10 항목 (W-2, W-3)
- **실패**: 1/10 항목 (보안 헤더 W-1)

핵심 인증·인가 메커니즘(BCrypt, Rate Limit, Scope, SSRF, SQL 검증, Admin 세션)은 정상 구현됨. Phase 0 출시 차단 이슈는 없으나, 보안 헤더(W-1)는 Admin SPA 보호를 위해 배포 전 적용 권장. PII fallback(W-2)과 IP 화이트리스트(W-3)는 Phase 1 이전 처리 권고.

---

*검토 기준: `requirements/07-auth-security.md` · ADR-0006 · ADR-0007 · ADR-0008 · LL-0001*
