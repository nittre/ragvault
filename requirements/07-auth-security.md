# 인증/인가 상세 설계

> Open WebUI ↔ Spring Boot 인증, 관리자 API 보호, Rate Limit, Audit Log.
> 대부분의 사용자 인증은 Open WebUI가 처리. 우리는 API Key 기반 보안에 집중.

관련 문서:
- [01-architecture.md](01-architecture.md)
- [03-data-sync-pipeline.md](03-data-sync-pipeline.md)
- [04-rag-search-strategy.md](04-rag-search-strategy.md)
- [08-text-to-sql.md](08-text-to-sql.md) — SQL 안전성 검증 + Read-only 계정 (6, 12절)

---

## 목차

1. [개요 및 결정사항](#1-개요-및-결정사항)
2. [인증 계층 구조](#2-인증-계층-구조)
3. [Open WebUI 사용자 인증](#3-open-webui-사용자-인증)
4. [Spring Boot API Key 인증](#4-spring-boot-api-key-인증)
5. [Scope 기반 권한](#5-scope-기반-권한)
6. [API Key 생애 주기](#6-api-key-생애-주기)
7. [관리자 API 보호](#7-관리자-api-보호)
8. [Rate Limiting](#8-rate-limiting)
9. [Audit Log](#9-audit-log)
10. [Open WebUI ↔ Spring Boot 연동](#10-open-webui--spring-boot-연동)
11. [SSO (Phase 2+)](#11-sso-phase-2)
12. [데이터 보존 정책](#12-데이터-보존-정책)
13. [Phase별 도입 계획](#13-phase별-도입-계획)
14. [DB 스키마 (인증 관련)](#14-db-스키마-인증-관련)

---

## 1. 개요 및 결정사항

### 핵심 결정사항

| 항목 | Phase 0 결정 |
|------|------------|
| Open WebUI 자동 가입 | 관리자 승인 필요 (pending → user) |
| 비밀번호 정책 | 최소 8자 + 영숫자 |
| API Key 만료 | 1년 |
| API Key 발급 | Spring Boot 관리자 API |
| 폐기된 키 보관 | 30일 (감사 추적용) |
| 관리자 API IP 제한 | 회사 IP 화이트리스트 |
| Rate Limit 단위 | **API Key별 + 사용자별 (이중)** |
| 사용자 식별 전달 | **Phase 0 — X-User-* 헤더** (Open WebUI **백엔드 프록시**가 주입, 클라이언트 주입 금지) |
| **사용자 식별자 정책 (N7)** | **`user_email` = 영구 식별자** (Phase 0 변경 불가). `audit_log` 에 `user_id (UUID)` + `user_email` 둘 다 저장 → 변경 추적 보장. Phase 1+ 이메일 변경 정책 검토 |
| **세션 자동 만료 (N8)** | Open WebUI 기본 (디폴트 8시간) + 다중 세션 허용 (사내 정책). 변경은 Phase 1+ |
| **원본 응답 short-lived 저장 (ADR-0010)** | LLM 응답을 Redis 30분 TTL 보존. 사용자 신고 시 admin 진단 가능. scope `api:incident-response` 추가 |
| **Admin UI 인증 흐름 (ADR-0009 / N2)** | admin UI → Spring Boot `AdminSessionFilter` → Open WebUI `/auth/verify` 호출 → role 확인 후 X-User-* 직접 세팅. ADR-0006 의 TrustedHeaderFilter 와 다른 경로 |
| Rate Limit 기본값 | 분당 60 / 시간당 1,000 / 일 10,000 |
| Audit Log 기록 | 비동기 (응답 빠름) |
| 채팅 응답 본문 기록 | 기록 안 함 (개인정보 보호) |
| SSO | Phase 2+ |
| 데이터 접근 그룹 (`user_groups`) | **Phase 0: 모든 사용자 `['all']` 부여**, Phase 1+ 부서/팀 그룹 도입 (옵션 D) |

---

## 2. 인증 계층 구조

```
[일반 사용자 흐름]
사용자
  │ 1. 이메일/비밀번호 로그인
  ▼
Open WebUI
  ├── 사용자 인증 (Open WebUI DB)
  ├── 세션 쿠키 발급
  └── 채팅 요청 시 ↓
      │
      │ 2. API Key 인증 (Bearer Token)
      ▼
Spring Boot OpenAI 호환 API
  ├── API Key 검증 (bcrypt)
  ├── Scope 체크 (api:chat)
  ├── Rate Limit (Redis)
  ├── audit_log 기록 (비동기)
  └── RAG 처리

[관리자 흐름]
관리자
  │ 1. 외부 도구 (curl, Postman, Terraform)
  ▼
ALB (IP 화이트리스트 — 회사 IP만)
  │
  ▼
Spring Boot Admin API
  ├── 관리자 API Key 검증
  ├── Scope 체크 (api:admin)
  └── 작업 수행
```

### 핵심 원칙

```
일반 사용자 인증 = Open WebUI 책임
└── 우리 Spring Boot는 사용자 인증 시스템 안 만듦

API 간 인증 = API Key (장기 토큰, bcrypt 해시)
└── JWT 자체 발급 시스템 없음 (Phase 0)

권한 = Scope 기반
└── api:chat, api:admin, api:sync, api:config, api:audit
```

---

## 3. Open WebUI 사용자 인증

Open WebUI가 모든 사용자 관련 인증을 처리.

### 기본 제공 기능

```
✓ 회원가입 / 로그인
✓ 비밀번호 해시 (bcrypt)
✓ 세션 쿠키 관리
✓ 비밀번호 재설정 (이메일)
✓ 사용자 역할 (admin, user, pending)
✓ 사용자 관리 UI
```

### 우리 설정 (helm values)

```yaml
# helm/open-webui/values.yaml
env:
  - name: ENABLE_SIGNUP
    value: "true"  # 가입 허용 (단, 승인 필요)
  - name: DEFAULT_USER_ROLE
    value: "pending"  # 신규 가입자는 pending → 관리자 승인 후 user
  - name: WEBUI_AUTH
    value: "true"
  - name: WEBUI_SECRET_KEY
    valueFrom:
      secretKeyRef:
        name: open-webui-secret
        key: secret-key
  - name: DATABASE_URL
    valueFrom:
      secretKeyRef:
        name: open-webui-secret
        key: db-url  # PostgreSQL (우리 RDS의 별도 DB)

  # 메일 발송 (상용만 — AWS SES, 02-stack-reference.md 참고)
  # 로컬·개발 환경에서는 아래 SMTP_* 블록 전체를 주석/비설정 → 메일 발송 비활성화
  - name: SMTP_HOST
    value: "email-smtp.ap-northeast-2.amazonaws.com"
  - name: SMTP_PORT
    value: "587"
  - name: SMTP_STARTTLS
    value: "true"
  - name: SMTP_FROM
    value: "noreply@{customer}.ragservice.com"
  - name: SMTP_USER
    valueFrom:
      secretKeyRef:
        name: ses-smtp-secret      # Secrets Manager에서 동기화
        key: smtp-user
  - name: SMTP_PASS
    valueFrom:
      secretKeyRef:
        name: ses-smtp-secret
        key: smtp-pass
```

> 로컬·개발 환경: SMTP_* 환경변수 미설정 → Open WebUI의 비밀번호 재설정 메일 발송 시도 시 silent fail.
> 비밀번호 재설정이 필요한 경우 관리자가 Open WebUI Admin Panel에서 수동으로 임시 비밀번호 발급.

### 비밀번호 정책 (Phase 0)

```
✓ 최소 8자
✓ 영문 + 숫자 조합 (특수문자는 권장만, 강제 X)
✗ 만료 정책 없음 (Phase 1+에서 90일 도입)
✗ 이전 비밀번호 재사용 금지 (Phase 1+)
```

### 초기 admin 계정 절차

```
[신규 고객사 온보딩 시]
1. Terraform으로 인프라 배포
2. 우리가 Open WebUI 초기 admin 계정 1개 생성
   - 이메일: ops@ragservice.com
   - 임시 비밀번호: 랜덤 16자
3. 고객사 관리자 정보 등록 요청
4. 우리가 고객사 관리자 계정을 admin으로 추가
5. 우리 초기 계정 비활성화 (감사 로그 보존)
6. 고객사가 자체적으로 사용자 추가/승인 진행
```

### 사용자 추가 절차

```
[고객사 관리자 작업]
1. Open WebUI Admin Panel 접근
2. Users 메뉴
3. 신규 사용자 직접 추가
   또는 사용자가 가입 (pending 상태)
   관리자가 user로 승인
```

---

## 4. Spring Boot API Key 인증

### API Key 형식

```
sk-rag-{20자 랜덤 영숫자}

[예시]
sk-rag-aBc1DeFgH2iJkLmNoP3q

[구성]
- prefix: 'sk-rag-' (식별용, DB 인덱스)
- random: 20자 (256bit 엔트로피)

[저장]
- 평문 절대 저장 안 함
- bcrypt 해시만 DB에
- prefix는 별도 컬럼 (빠른 후보 조회)
```

### api_keys 테이블

```sql
CREATE TABLE api_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL,         -- 'open-webui-prod'
    key_hash        VARCHAR(255) NOT NULL,         -- bcrypt
    key_prefix      VARCHAR(20) NOT NULL,          -- 'sk-rag-aBc1Def...' (앞 15자)
    scopes          TEXT[] NOT NULL,               -- ['api:chat']
    is_active       BOOLEAN DEFAULT true,
    expires_at      TIMESTAMP NOT NULL,            -- 발급 시점 + 1년
    last_used_at    TIMESTAMP,
    created_by      VARCHAR(200),
    created_at      TIMESTAMP DEFAULT NOW(),
    deactivated_at  TIMESTAMP,
    UNIQUE (key_hash)
);

CREATE INDEX idx_apikeys_prefix ON api_keys (key_prefix);
CREATE INDEX idx_apikeys_active ON api_keys (is_active, expires_at);
```

### 검증 흐름

```java
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        
        // 헬스체크는 통과
        if (request.getRequestURI().startsWith("/api/v1/health")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            unauthorized(response, "API Key required");
            return;
        }
        
        String token = authHeader.substring(7);
        String prefix = token.substring(0, Math.min(15, token.length()));
        
        // 1. prefix로 빠르게 후보 조회
        List<ApiKey> candidates = apiKeyRepository.findActiveByPrefix(prefix);
        
        // 2. bcrypt로 매칭 (시간 일정 비교)
        ApiKey matched = candidates.stream()
            .filter(k -> passwordEncoder.matches(token, k.getKeyHash()))
            .findFirst()
            .orElse(null);
        
        if (matched == null) {
            unauthorized(response, "Invalid API key");
            return;
        }
        
        // 3. 만료 검사
        if (matched.getExpiresAt().isBefore(Instant.now())) {
            unauthorized(response, "API key expired");
            return;
        }
        
        // 4. SecurityContext에 권한 정보 저장
        List<GrantedAuthority> authorities = matched.getScopes().stream()
            .map(SimpleGrantedAuthority::new)
            .collect(toList());
        
        Authentication auth = new ApiKeyAuthentication(matched, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
        
        // 5. last_used_at 업데이트 (비동기, 응답 지연 방지)
        asyncService.updateLastUsed(matched.getId());
        
        filterChain.doFilter(request, response);
    }
}
```

### Spring Security 설정

```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/health/**").permitAll()
                .requestMatchers("/v1/models").hasAuthority("api:chat")
                .requestMatchers("/v1/chat/**").hasAuthority("api:chat")
                .requestMatchers("/api/v1/admin/**").hasAuthority("api:admin")
                .anyRequest().authenticated()
            )
            .build();
    }
}
```

---

## 5. Scope 기반 권한

### Scope 정의

```
[기본 Scope]
api:chat        — RAG 채팅 API 호출 (POST /v1/chat/completions)
api:models      — 모델 목록 조회 (GET /v1/models)

[관리 Scope]
api:admin       — 관리자 API 전체 (포괄적)
api:sync        — 데이터 동기화 트리거
api:config      — 설정 변경 (search_config, rag_table_config)
api:audit       — 감사 로그 조회
api:apikey      — API Key 발급/폐기
```

### 엔드포인트별 요구 Scope

| 엔드포인트 | 필요 Scope |
|----------|-----------|
| POST /v1/chat/completions | api:chat |
| GET /v1/models | api:chat 또는 api:models |
| POST /api/v1/admin/sync/trigger | api:admin + api:sync |
| GET /api/v1/admin/sync/status | api:admin + api:sync |
| GET /api/v1/admin/audit-logs | api:admin + api:audit |
| PATCH /api/v1/admin/search-config | api:admin + api:config |
| POST /api/v1/admin/rag-tables | api:admin + api:config |
| POST /api/v1/admin/api-keys | api:admin + api:apikey |

### 메서드 레벨 보호

```java
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    
    @PostMapping("/sync/trigger")
    @PreAuthorize("hasAllAuthorities('api:admin', 'api:sync')")
    public ResponseEntity<?> triggerSync() {
        // ...
    }
    
    @GetMapping("/audit-logs")
    @PreAuthorize("hasAllAuthorities('api:admin', 'api:audit')")
    public ResponseEntity<?> getAuditLogs(...) {
        // ...
    }
    
    @PostMapping("/api-keys")
    @PreAuthorize("hasAllAuthorities('api:admin', 'api:apikey')")
    public ResponseEntity<?> createApiKey(@RequestBody CreateApiKeyRequest req) {
        // ...
    }
}
```

### Scope 조합 예시

```
[Open WebUI용 키]
name: "open-webui-prod"
scopes: ["api:chat"]
→ 채팅만 가능

[Jenkins 배포용 키]
name: "jenkins-deploy"
scopes: ["api:admin", "api:sync", "api:config"]
→ 배포/동기화/설정 변경

[감사 시스템용 키]
name: "audit-exporter"
scopes: ["api:admin", "api:audit"]
→ 감사 로그만 조회 (다른 권한 없음)

[슈퍼 관리자 키]
name: "superadmin"
scopes: ["api:admin", "api:sync", "api:config", "api:audit", "api:apikey"]
→ 모든 작업 가능
```

### Scope vs 데이터 접근 그룹 (구분)

```
[Scope = 시스템 권한]
- 어느 엔드포인트를 호출할 수 있는가
- api:chat, api:admin, api:sync, api:config, api:audit, api:apikey
- API Key에 부여

[데이터 접근 그룹 = 검색 대상 권한]
- 어느 RAG 청크 / SQL 테이블을 볼 수 있는가
- 'all', 'hr', 'engineering', 'finance' 등 도메인 그룹
- 사용자(user_email)에 부여 (Phase 1+)
- Phase 0: 모든 사용자에게 ['all'] 고정 부여

[왜 분리하는가]
- api:chat 가진 사용자라도 HR 데이터에 접근 안 될 수 있어야 함 (Phase 1+)
- 같은 채팅 엔드포인트로 호출하지만 검색 결과는 사용자별로 다름
- Scope를 세분화하면 API Key 폭증 + 운영 부담 ↑
- 데이터 권한은 검색 쿼리의 `access_groups && user_groups` 로 분리
```

### user_groups 스키마 (Phase 1+ 도입, 스키마는 Phase 0에 미리)

```sql
-- Phase 0에 테이블만 만들고 모든 사용자에게 ['all']만 부여
-- Phase 1+에 그룹 정의 추가
CREATE TABLE user_groups (
    user_email      VARCHAR(200) NOT NULL,
    group_name      VARCHAR(100) NOT NULL,
    granted_by      VARCHAR(200),
    granted_at      TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (user_email, group_name)
);

CREATE INDEX idx_user_groups_email ON user_groups (user_email);

-- Phase 0 초기 데이터: 모든 신규 사용자에게 자동으로 'all' 부여
-- (Open WebUI user pending → user 승격 시점 트리거)

-- 그룹 카탈로그 (Phase 1+ 활성)
CREATE TABLE group_definitions (
    group_name      VARCHAR(100) PRIMARY KEY,
    display_name    VARCHAR(200),
    description     TEXT,
    is_active       BOOLEAN DEFAULT true,
    created_at      TIMESTAMP DEFAULT NOW()
);

INSERT INTO group_definitions (group_name, display_name, description) VALUES
('all', '전체', 'Phase 0 기본 그룹 — 모든 사용자가 속함');
```

### 검색 시점 그룹 해결

```java
// Phase 0
List<String> userGroups = List.of("all");

// Phase 1+
List<String> userGroups = userGroupRepository.findGroupsByEmail(userEmail);
if (!userGroups.contains("all")) {
    userGroups = new ArrayList<>(userGroups);
    userGroups.add("all");   // 공통 그룹은 항상 포함
}

// 04 문서의 검색 쿼리 $3 파라미터로 전달
List<Chunk> chunks = chunkRepo.findTopK(
    embedding,
    threshold,
    topK,
    userGroups.toArray(new String[0])
);
```

---

## 6. API Key 생애 주기

### 발급

```http
POST /api/v1/admin/api-keys
Authorization: Bearer {admin-api-key}
Content-Type: application/json

{
  "name": "open-webui-prod",
  "scopes": ["api:chat"],
  "expires_in_days": 365
}
```

**응답**:
```json
{
  "id": "uuid",
  "key": "sk-rag-aBc1DeFgH2iJkLmNoP3q",
  "name": "open-webui-prod",
  "scopes": ["api:chat"],
  "expires_at": "2027-05-12T00:00:00Z",
  "warning": "이 키는 다시 표시되지 않습니다. 안전한 곳에 보관하세요."
}
```

→ `key` 평문은 응답에 **한 번만** 표시. 이후 조회 불가.

### 사용

```http
POST /v1/chat/completions
Authorization: Bearer sk-rag-aBc1DeFgH2iJkLmNoP3q
Content-Type: application/json

{
  "model": "company-rag-balanced",
  "messages": [...]
}
```

**Spring Boot 검증 흐름**:
```
1. Header에서 Bearer 토큰 추출
2. prefix(15자)로 DB 후보 조회 (인덱스 활용)
3. bcrypt로 매칭 확인
4. is_active 체크
5. expires_at 검증
6. scopes 검증 (요청 엔드포인트에 따라)
7. last_used_at 비동기 업데이트
```

### 회전 (Rotation)

```
[정기 회전 — 1년마다]
1. 새 API Key 발급 (만료 30일 전)
2. Open WebUI 설정에 새 키 반영
3. 24~48시간 동안 두 키 모두 유효
4. last_used_at 모니터링 (구 키 사용 없음 확인)
5. 구 키 폐기 (is_active=false)

[비상 회전 — 키 노출 의심]
1. 구 키 즉시 폐기
2. 새 키 발급
3. Open WebUI 긴급 업데이트
4. audit_log 검토 (오용 흔적)
5. 보안 사고 보고서 작성
```

### 폐기

```http
DELETE /api/v1/admin/api-keys/{id}
Authorization: Bearer {admin-api-key}
```

**동작**:
```
1. is_active = false
2. deactivated_at = NOW()
3. DB에서 즉시 삭제 안 함 (감사 추적 위해 30일 보관)
4. 30일 후 cron job이 자동 삭제

cron 작업:
DELETE FROM api_keys
WHERE is_active = false
  AND deactivated_at < NOW() - INTERVAL '30 days';
```

### 만료 임박 알림

```
[Discord 알람]
매일 새벽 4시 cron:
SELECT * FROM api_keys
WHERE is_active = true
  AND expires_at < NOW() + INTERVAL '30 days';

→ 만료 30일 전부터 알람:
"⚠️ API Key 'open-webui-prod' 만료 임박 (D-30)"

→ 만료 7일 전: Critical 알람
→ 만료 1일 전: 긴급 알람
```

---

## 7. 관리자 API 보호

### Phase 0: API Key + IP 화이트리스트

```
[관리자 API Key]
- 별도 발급 (scope: api:admin + 세부 scope)
- 외부 도구 (Terraform, Jenkins)에서 사용
- 1년 만료, 정기 회전

[IP 화이트리스트]
ALB 보안 그룹 + Spring Boot 필터에서 이중 검증

허용 IP:
- 회사 사무실 IP
- VPN 게이트웨이 IP
- Jenkins 서버 IP
```

### Spring Boot IP 검증 필터

```java
@Component
public class AdminIpFilter extends OncePerRequestFilter {
    
    @Value("${admin.allowed-ips}")
    private List<String> allowedIps;
    
    @Override
    protected void doFilterInternal(...) throws ... {
        // 관리자 API만 검증
        if (!request.getRequestURI().startsWith("/api/v1/admin")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        String clientIp = extractClientIp(request);  // X-Forwarded-For 고려
        
        if (!isAllowed(clientIp, allowedIps)) {
            log.warn("Admin API access denied from IP: {}", clientIp);
            response.sendError(403, "Access denied");
            return;
        }
        
        filterChain.doFilter(request, response);
    }
    
    private boolean isAllowed(String clientIp, List<String> allowed) {
        return allowed.stream().anyMatch(cidr -> matchesCidr(clientIp, cidr));
    }
}
```

### 설정 예시

```yaml
# application-prod.yml
admin:
  allowed-ips:
    - "203.0.113.0/24"   # 회사 사무실 대역
    - "198.51.100.10/32" # Jenkins 서버
    - "192.0.2.0/29"     # VPN 게이트웨이
```

### ALB 보안 그룹

```hcl
# Terraform
resource "aws_security_group_rule" "admin_api_ip_allow" {
  type              = "ingress"
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = var.admin_allowed_ips
  security_group_id = aws_security_group.alb.id
}
```

---

## 8. Rate Limiting

### Redis 카운터 기반

```java
@Component
public class RateLimiter {
    
    private final StringRedisTemplate redis;
    
    public boolean allow(String apiKeyId, RateLimitTier tier) {
        boolean perMinuteOk = checkLimit(
            "rl:min:" + apiKeyId + ":" + currentMinute(),
            60,
            tier.getPerMinute()
        );
        boolean perHourOk = checkLimit(
            "rl:hr:" + apiKeyId + ":" + currentHour(),
            3600,
            tier.getPerHour()
        );
        boolean perDayOk = checkLimit(
            "rl:day:" + apiKeyId + ":" + currentDay(),
            86400,
            tier.getPerDay()
        );
        
        return perMinuteOk && perHourOk && perDayOk;
    }
    
    private boolean checkLimit(String key, int ttlSec, int limit) {
        Long count = redis.opsForValue().increment(key);
        if (count == 1) redis.expire(key, Duration.ofSeconds(ttlSec * 2));
        return count <= limit;
    }
}
```

### Phase 0 기본 정책

| Scope | 분당 | 시간당 | 일일 |
|-------|------|-------|------|
| api:chat | **60** | **1,000** | **10,000** |
| api:admin | 100 | 5,000 | 20,000 |
| api:audit | 30 | 500 | 5,000 |

### 동적 설정 (search_config 활용)

```sql
INSERT INTO search_config (config_key, config_value, description) VALUES
('rate_limit_chat_per_minute', '60', 'api:chat 분당 한도'),
('rate_limit_chat_per_hour', '1000', 'api:chat 시간당 한도'),
('rate_limit_chat_per_day', '10000', 'api:chat 일일 한도'),
('rate_limit_admin_per_minute', '100', 'api:admin 분당 한도'),
('rate_limit_admin_per_hour', '5000', 'api:admin 시간당 한도'),
('rate_limit_admin_per_day', '20000', 'api:admin 일일 한도');
```

### 초과 시 응답

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 60
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1715424600
Content-Type: application/json

{
  "error": {
    "code": "rate_limit_exceeded",
    "message": "사용량 한도에 도달했습니다. 60초 후 다시 시도해주세요.",
    "error_id": "err_rate_001"
  }
}
```

### Phase 0 사용자별 Rate Limit (변경됨)

> [09-user-parameter-tuning.md](09-user-parameter-tuning.md)에서 사용자 식별이 필요해짐에 따라
> X-User-Email 헤더 전달을 Phase 0으로 변경.

```
[Open WebUI 백엔드(Python/FastAPI)가 프록시 시 헤더 주입]
POST /v1/chat/completions  (Open WebUI 백엔드 → Spring Boot)
Authorization: Bearer sk-rag-...           ← 백엔드에 보관된 API Key
X-User-Email: user@customer.com            ← Phase 0 도입, 세션 인증 결과
X-User-Id: 12345                           ← Phase 0 도입
X-User-Role: user                          ← Phase 0 도입

※ 브라우저(Svelte) 코드는 X-User-* 헤더를 추가하지 않는다.
※ 클라이언트가 보낸 X-User-* 헤더는 백엔드 프록시에서 폐기 후 새로 구성한다.

[이중 Rate Limit]
Redis 카운터 키:
- rl:apikey:{api_key_id}:min:202605121030   (API Key별)
- rl:user:user@customer.com:min:202605121030 (사용자별)

→ 둘 중 하나라도 초과 시 차단
→ 1명 사용자 폭주가 다른 사용자에 영향 없음

[헤더 보안]
- 내부 IP(k3s VPC 대역)에서만 X-User-* 헤더 신뢰
- 외부에서 직접 호출 시 헤더 폐기 (위변조 방지)
- 헤더 형식 검증 (이메일 포맷 등)
```

### 사용자 식별 흐름

```
[Phase 0 흐름]
1. 사용자 브라우저 → Open WebUI 로그인 (이메일/비밀번호)
2. Open WebUI 백엔드가 세션 쿠키 발급
3. 사용자가 채팅 메시지 전송
   브라우저 → Open WebUI 백엔드 (세션 쿠키 + body의 rag_params만)
4. Open WebUI 백엔드 프록시 (Python/FastAPI):
   - 세션으로 사용자 인증
   - 클라이언트가 보낸 X-User-* 헤더는 폐기
   - 신규 헤더 구성:
     · Authorization: Bearer sk-rag-...  (백엔드에 안전 보관)
     · X-User-Email: 세션 사용자 이메일
     · X-User-Id:    Open WebUI 내부 사용자 ID
     · X-User-Role:  admin / user / pending
   - Spring Boot로 프록시 (k3s 내부 통신)
5. Spring Boot 수신:
   - TrustedHeaderFilter: 발신 IP가 k3s VPC 내부인지 검증
     · 외부 IP → X-User-* 제거 (API Key 인증만 사용)
     · 내부 IP → X-User-* 신뢰
   - ApiKeyAuthFilter: API Key 검증 (전체 시스템 인증)
   - X-User-Email로 사용자 식별 (애플리케이션 레벨)
   - UserContext.set() (ThreadLocal)
6. 사용자 프로필 조회, Rate Limit 체크, audit_log 기록 시 사용

[보안 검증 — Spring Boot 측]
TrustedHeaderFilter가 X-User-* 헤더 검증:
- 발신 IP가 내부 (k3s VPC) 인지 확인 (X-Forwarded-For 최우측 신뢰 IP 사용)
- 외부 IP면 X-User-* 헤더 제거 (위변조 방지)
- 내부 IP면 이메일 형식 등 sanity check 후 통과
```

---

## 9. Audit Log

### audit_logs 테이블

```sql
CREATE TABLE audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    
    -- Who
    api_key_id      UUID REFERENCES api_keys(id),
    api_key_name    VARCHAR(100),
    user_email      VARCHAR(200),
    ip_address      VARCHAR(50),
    user_agent      VARCHAR(500),
    
    -- What
    action          VARCHAR(100) NOT NULL,
    resource        VARCHAR(500),
    method          VARCHAR(10),
    path            VARCHAR(500),
    
    -- Result
    status          VARCHAR(20),
    status_code     INT,
    response_time_ms INT,
    error_id        VARCHAR(20),
    
    -- Metadata
    metadata        JSONB,
    
    -- When
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_user ON audit_logs (user_email, created_at DESC);
CREATE INDEX idx_audit_logs_action ON audit_logs (action, created_at DESC);
CREATE INDEX idx_audit_logs_api_key ON audit_logs (api_key_id, created_at DESC);
CREATE INDEX idx_audit_logs_created ON audit_logs (created_at);
```

### 기록 대상

```
[필수 기록]
✓ 모든 채팅 요청 (질문은 PII 마스킹 후 저장)
✓ 관리자 작업 (동기화, 설정 변경, API Key 발급/폐기)
✓ 인증 실패 (잘못된 API Key)
✓ Rate Limit 초과
✓ 권한 부족 (403)
✓ 에러 응답 (오류 ID 포함)

[기록 안 함]
✗ 헬스체크 (/api/v1/health/**)
✗ 채팅 응답 본문 (개인정보 위험)
✗ 정상 GET 요청 본문
```

### 비동기 기록

```java
@Component
public class AsyncAuditLogger {
    
    private final BlockingQueue<AuditLogEntry> queue = new LinkedBlockingQueue<>(10000);
    
    @PostConstruct
    public void start() {
        executor.execute(this::processQueue);
    }
    
    public void log(AuditLogEntry entry) {
        // 비동기 큐에 추가 (응답 지연 없음)
        boolean added = queue.offer(entry);
        if (!added) {
            log.warn("Audit log queue full, dropping entry");
            metrics.incrementCounter("audit_log_drops");
        }
    }
    
    private void processQueue() {
        while (true) {
            try {
                // 배치 처리 (100건씩 또는 1초마다)
                List<AuditLogEntry> batch = drainBatch(100, Duration.ofSeconds(1));
                if (!batch.isEmpty()) {
                    auditLogRepository.saveAll(batch);
                }
            } catch (Exception ex) {
                log.error("Audit log batch failed", ex);
            }
        }
    }
}
```

### 채팅 요청 기록 (PII 마스킹)

```
[원본 질문]
"홍길동(010-1234-5678)의 계약 정보 알려줘"

[마스킹 후 저장 (resource 컬럼)]
"홍길동([전화번호])의 계약 정보 알려줘"

→ 정규식 마스킹 적용
→ 이름은 마스킹 안 됨 (Phase 0)
→ Phase 1+ NER로 강화
```

### 응답 본문 미저장

```
[정책]
LLM 응답 본문은 audit_logs에 저장 안 함

[이유]
1. 개인정보 노출 위험 (LLM이 답변에 PII 포함 가능성)
2. 저장 용량 부담 (응답이 김)
3. 감사 추적은 "누가 무엇을 물었는가"로 충분

[필요 시 추적]
response_id로 logs와 연결
→ 사용자 신고 시 CloudWatch Logs에서 검색
→ 30일 후 자동 삭제 (CloudWatch 보존 정책)
```

### 보존 기간 및 삭제

```sql
-- 1년 후 자동 삭제 (매일 새벽 3시 cron)
DELETE FROM audit_logs
WHERE created_at < NOW() - INTERVAL '1 year';

-- 또는 PostgreSQL 파티셔닝 (Phase 1+ 데이터 많아질 때)
CREATE TABLE audit_logs_2026_05 PARTITION OF audit_logs
FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
```

### 관리자 조회 API

```http
GET /api/v1/admin/audit-logs?user_email=user@customer.com&action=rag_query&from=2026-05-01&to=2026-05-12&limit=100
Authorization: Bearer {admin-api-key with api:audit}
```

**응답**:
```json
{
  "items": [
    {
      "id": 12345,
      "user_email": "user@customer.com",
      "action": "rag_query",
      "resource": "A 상품 [전화번호] 보증 기간",
      "status": "success",
      "response_time_ms": 850,
      "created_at": "2026-05-12T10:30:00Z"
    }
  ],
  "total": 1500,
  "page": 1,
  "limit": 100
}
```

---

## 10. Open WebUI ↔ Spring Boot 연동

### Open WebUI 설정

```
[Open WebUI Admin Panel]
Settings → Connections → OpenAI API

URL:     http://rag-backend.rag.svc.cluster.local:8080/v1
API Key: sk-rag-aBc1DeFgH2iJkLmNoP3q
Default Model: company-rag-balanced
```

→ 모든 채팅 요청을 우리 Spring Boot로 라우팅

### 모델 변형 등록

```
[Open WebUI에 노출되는 모델 목록]
- company-rag-precise   (K=3, T=0.75)
- company-rag-balanced  (K=5, T=0.65) ← 기본
- company-rag-broad     (K=10, T=0.55)

사용자가 채팅 화면 상단에서 모델 선택 가능
```

### /v1/models 엔드포인트 응답

```http
GET /v1/models
Authorization: Bearer sk-rag-...

{
  "object": "list",
  "data": [
    {
      "id": "company-rag-precise",
      "object": "model",
      "created": 1715424600,
      "owned_by": "company-rag"
    },
    {
      "id": "company-rag-balanced",
      "object": "model",
      "created": 1715424600,
      "owned_by": "company-rag"
    },
    {
      "id": "company-rag-broad",
      "object": "model",
      "created": 1715424600,
      "owned_by": "company-rag"
    }
  ]
}
```

### 자격증명 보관

```
[Open WebUI 측]
API Key는 Open WebUI DB(PostgreSQL)에 암호화 저장
→ Open WebUI가 자체 처리 (우리 책임 아님)

[Spring Boot 측]
DB(api_keys 테이블)에 bcrypt 해시만 저장
원본 키는 발급 시점 1회만 노출
```

### 사용자 정보 전달 (Phase 0 — Open WebUI 백엔드 프록시)

> [09-user-parameter-tuning.md 섹션 6](09-user-parameter-tuning.md#6-open-webui-통합-방법)과 정합.
> Phase 0부터 도입. 사용자별 Rate Limit과 파라미터 프로필이 이 헤더에 의존.

```
[목적]
- 사용자별 Rate Limit (API Key별 + 사용자별 이중)
- audit_log의 user_email 정확성
- 사용자 파라미터 프로필 적용

[원칙 — 헤더는 백엔드에서만 주입]
브라우저(클라이언트)에서 X-User-* 헤더를 추가하는 것은 금지.
사용자가 임의로 위변조 가능하기 때문 (다른 사용자 사칭 / 권한 상승).

→ Open WebUI 백엔드(Python/FastAPI)가 세션 인증 후 헤더를 주입한다.
→ Spring Boot는 발신 IP가 k3s 내부인 경우에만 헤더를 신뢰한다.

[헤더]
X-User-Email: user@customer.com   (Open WebUI 인증 사용자의 이메일)
X-User-Id:    12345               (Open WebUI 내부 사용자 ID, 문자열)
X-User-Role:  user                (admin | user | pending)

[구현 위치]
- Open WebUI Fork: backend의 /v1/chat/completions 프록시 라우트
  → 클라이언트가 보낸 X-User-* 헤더는 무시하고, 세션 사용자 정보로 새로 구성
- Spring Boot: TrustedHeaderFilter
  → 외부 IP에서 온 요청은 X-User-* 헤더를 제거 후 다음 필터로 전달
```

### 신뢰 경계 (Trust Boundary)

```
┌──────────────────────────────────────────────────────────────┐
│  외부 (불신뢰 영역)                                            │
│                                                              │
│   브라우저 ── 세션 쿠키만 ───────────────┐                    │
│                                          ▼                   │
├──────────────────────────────────────────────────────────────┤
│  k3s VPC 내부 (신뢰 영역)                                      │
│                                                              │
│   Open WebUI Pod                                             │
│     │ 세션 인증된 user 정보로                                  │
│     │ X-User-Email / X-User-Id / X-User-Role 주입            │
│     │ (클라이언트 헤더는 폐기)                                 │
│     ▼                                                         │
│   Spring Boot Pod                                            │
│     ├── TrustedHeaderFilter: 발신 IP가 VPC 내부인지 검증       │
│     ├── 외부 IP → X-User-* 제거                              │
│     └── 내부 IP → X-User-* 신뢰                              │
└──────────────────────────────────────────────────────────────┘
```

### 외부 직접 호출 시나리오 (예: Jenkins, curl)

```
[관리자가 curl로 Spring Boot 직접 호출]
- 발신 IP가 회사 사무실/Jenkins → admin IP 화이트리스트 통과
- 그러나 X-User-* 헤더는 외부 IP에서 들어왔으므로 제거됨
- audit_log.user_email 은 NULL 또는 API Key name으로 대체 기록
- 사용자별 Rate Limit 미적용, API Key별 Rate Limit만 적용

→ 정상 동작. 관리자 작업은 사용자 컨텍스트가 필요 없음.
```

---

## 11. SSO (Phase 2+)

### 시나리오

```
[엔터프라이즈 고객사 요구]
"우리 회사 직원 5,000명을 일일이 가입시키라고?
 우리 SSO랑 연동해줘"

[지원 프로토콜]
- OIDC (OpenID Connect) — 최우선
- SAML 2.0 — 대기업 표준
- LDAP / Active Directory — 온프레미스
```

### Open WebUI OIDC 설정

```yaml
env:
  - name: ENABLE_OAUTH_SIGNUP
    value: "true"
  - name: OAUTH_CLIENT_ID
    valueFrom:
      secretKeyRef:
        name: oauth-secret
        key: client-id
  - name: OAUTH_CLIENT_SECRET
    valueFrom:
      secretKeyRef:
        name: oauth-secret
        key: client-secret
  - name: OPENID_PROVIDER_URL
    value: "https://login.customer.com/.well-known/openid-configuration"
  - name: OAUTH_PROVIDER_NAME
    value: "Customer SSO"
  - name: OAUTH_SCOPES
    value: "openid email profile"
```

### 흐름

```
사용자 → Open WebUI 접근
        ↓
"Customer SSO로 로그인" 클릭
        ↓
고객사 SSO 페이지 (예: Azure AD)
        ↓
사용자 인증 + 권한 동의
        ↓
Open WebUI로 redirect (OIDC 토큰)
        ↓
Open WebUI가 토큰 검증 + 사용자 정보 추출
        ↓
첫 로그인이면 자동 가입 (또는 pending)
        ↓
세션 발급 후 채팅 시작
```

### 도입 시점

```
☑ Phase 2+ — 엔터프라이즈 고객 유치 시
```

---

## 12. 데이터 보존 정책

> 모호 #8 해결 — 데이터 종류별 보존 기간 명시.
> 자동 삭제 cron으로 운영 관리.

### 보존 기간 표 (전체)

| 데이터 종류 | 보존 기간 | 정책 | 비고 |
|------------|---------|------|------|
| **운영 로그 (1년)** | | | |
| audit_logs | 1년 | 자동 삭제 | 컴플라이언스 |
| error_logs | 1년 | 자동 삭제 | 디버깅 + 추적 |
| sql_execution_log | 1년 | 자동 삭제 | SQL 의심 패턴 분석 |
| sync_log | 1년 | 자동 삭제 | 동기화 이력 |
| **단기 추적 (30~90일)** | | | |
| binlog_events | 30일 | 자동 삭제 | 디버깅용 |
| ddl_events (처리 완료) | 30일 | 자동 삭제 | 미처리는 영구 |
| sync_jobs | 90일 | 자동 삭제 | 작업 단위 |
| conversation_param_overrides | 90일 | inactive 대화 기준 | 대화 종료 후 |
| **API Key** | | | |
| api_keys (활성) | 1년 만료 | 만료 임박 알람 | 03-auth |
| api_keys (폐기) | 30일 보관 | 자동 삭제 | 감사 추적 |
| **설정 (영구)** | | | |
| rag_table_config | 영구 | 수동 삭제만 | 운영 설정 |
| sql_table_config | 영구 | 수동 삭제만 | 운영 설정 |
| search_config | 영구 | 수동 삭제만 | 운영 설정 |
| admin_param_limits | 영구 | 수동 삭제만 | 관리자 정책 |
| model_variants | 영구 | 수동 삭제만 | 모델 정의 |
| user_param_profiles | 영구 | 사용자 삭제 시 | 사용자 선호 |
| **벡터 데이터** | | | |
| document_chunks (활성) | 영구 | 계약 종료 시까지 | 고객사 데이터 |
| document_chunks (Offboard) | 30일 grace | 후 완전 삭제 | 계약 종료 시 |
| **백업** | | | |
| RDS 자동 스냅샷 | 7일 | AWS 관리 | 단기 PITR |
| RDS 매주 백업 → S3 | 30일 (S3) | S3 lifecycle | 중기 |
| S3 → Glacier | 12개월 | Glacier | 장기/감사 |
| Glacier 이후 | 삭제 | 자동 | 12개월 후 |
| **휘발성 (단기)** | | | |
| URL Fetch 캐시 | 24시간 | Redis TTL | 대화 한정 |
| 첨부 파일 | 24시간 | S3 lifecycle | 대화 한정 |
| 이미지 임시 업로드 | 24시간 | S3 TTL | Vision LLM 처리 후 |
| 의도 분류 캐시 | 24시간 | Redis TTL | 성능 |
| 스키마 캐시 | 1시간 | Redis TTL | 빈번 갱신 |
| **평가 (Phase 1+)** | | | |
| evaluation_set | 영구 | 운영 자산 | Golden Dataset |
| evaluation_runs | 1년 | 자동 삭제 | 평가 이력 |

### retention_policies 테이블 (운영 표준)

```sql
CREATE TABLE retention_policies (
    id                  SERIAL PRIMARY KEY,
    data_type           VARCHAR(100) NOT NULL UNIQUE,
    retention_days      INT,                    -- NULL = 영구
    archive_after_days  INT,                    -- Glacier 이동 시점
    delete_after_days   INT,                    -- 완전 삭제 시점
    legal_hold          BOOLEAN DEFAULT false,  -- 법적 보관 (삭제 차단)
    notes               TEXT,
    updated_at          TIMESTAMP DEFAULT NOW()
);

INSERT INTO retention_policies (data_type, retention_days, delete_after_days, notes) VALUES
('audit_logs', 365, 365, '컴플라이언스 1년 보관'),
('error_logs', 365, 365, '에러 추적'),
('sql_execution_log', 365, 365, 'SQL 의심 패턴 분석'),
('sync_log', 365, 365, '동기화 이력'),
('binlog_events', 30, 30, '디버깅용 단기'),
('ddl_events_processed', 30, 30, '처리 완료된 DDL만'),
('sync_jobs', 90, 90, '작업 단위 추적'),
('conversation_param_overrides', 90, 90, 'inactive 대화 기준'),
('api_keys_deactivated', 30, 30, '폐기된 키 감사용'),
('document_chunks_offboarded', 30, 30, '계약 종료 후 grace'),
('rds_snapshot_weekly', 30, 360, 'S3 30일 + Glacier 12개월'),
('evaluation_runs', 365, 365, 'Phase 1+'),
('rag_table_config', NULL, NULL, '영구 (수동 삭제만)'),
('sql_table_config', NULL, NULL, '영구'),
('search_config', NULL, NULL, '영구'),
('admin_param_limits', NULL, NULL, '영구'),
('user_param_profiles', NULL, NULL, '사용자 삭제 시'),
('document_chunks_active', NULL, NULL, '계약 종료 시까지');
```

### 자동 삭제 cron (Spring @Scheduled)

```java
@Component
public class DataRetentionScheduler {
    
    // 매일 새벽 3시: 1년 보존 데이터 삭제
    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "retention-1year")
    public void cleanupYearlyData() {
        var oneYearAgo = LocalDateTime.now().minusYears(1);
        
        auditLogRepo.deleteByCreatedAtBefore(oneYearAgo);
        errorLogRepo.deleteByCreatedAtBefore(oneYearAgo);
        sqlExecutionLogRepo.deleteByCreatedAtBefore(oneYearAgo);
        syncLogRepo.deleteByCreatedAtBefore(oneYearAgo);
        
        log.info("Yearly retention cleanup completed");
    }
    
    // 매일 새벽 4시: 30~90일 보존 데이터 삭제
    @Scheduled(cron = "0 0 4 * * *")
    @SchedulerLock(name = "retention-short")
    public void cleanupShortTermData() {
        var thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        var ninetyDaysAgo = LocalDateTime.now().minusDays(90);
        
        binlogEventRepo.deleteByCreatedAtBefore(thirtyDaysAgo);
        ddlEventRepo.deleteProcessedBefore(thirtyDaysAgo);
        syncJobRepo.deleteByStartedAtBefore(ninetyDaysAgo);
        conversationOverrideRepo.deleteInactiveBefore(ninetyDaysAgo);
        
        log.info("Short-term retention cleanup completed");
    }
    
    // 매일 새벽 5시: 폐기 키 삭제
    @Scheduled(cron = "0 0 5 * * *")
    @SchedulerLock(name = "retention-apikeys")
    public void cleanupDeactivatedKeys() {
        var thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        apiKeyRepo.deleteDeactivatedBefore(thirtyDaysAgo);
    }
    
    // 매주 일요일 새벽 2시: 휘발성 데이터
    @Scheduled(cron = "0 0 2 * * SUN")
    @SchedulerLock(name = "retention-volatile")
    public void cleanupVolatileData() {
        // S3 lifecycle policy로 처리되지만
        // 누락된 객체 보강 삭제
        s3CleanupService.deleteOldAttachments();
        s3CleanupService.deleteOldImageUploads();
    }
}
```

### S3 Lifecycle 정책 (Terraform)

```hcl
# 첨부 파일/이미지 (24시간 TTL)
resource "aws_s3_bucket_lifecycle_configuration" "attachments" {
  bucket = aws_s3_bucket.attachments.id
  
  rule {
    id     = "delete-after-24h"
    status = "Enabled"
    
    expiration {
      days = 1
    }
  }
}

# RDS 백업 (30일 S3 + 12개월 Glacier)
resource "aws_s3_bucket_lifecycle_configuration" "rds_backups" {
  bucket = aws_s3_bucket.rds_backups.id
  
  rule {
    id     = "transition-to-glacier"
    status = "Enabled"
    
    transition {
      days          = 30
      storage_class = "GLACIER"
    }
    
    expiration {
      days = 390  # 30 + 360
    }
  }
}
```

### 고객사 Offboarding 절차

```
[계약 종료 통보 받음]
        ↓
[D-day - 30일] Grace Period 시작
- 고객사에 30일 전 사전 안내
- 데이터 export 요청 받음 (선택)
- 우리 측 데이터 변경 중단 (read-only 모드)
        ↓
[D-day] 운영 중단
- Open WebUI 접근 차단
- API 호출 차단
- 인프라 stop (RDS는 유지)
        ↓
[D-day + 30일] 완전 삭제
- document_chunks 전체 DELETE
- 고객사 RDS 스냅샷 → 1회 삭제 검증 후 삭제
- audit_logs는 PII 마스킹 후 보관 (감사용)
- S3 객체 모두 삭제
- AWS 인프라 destroy (Terraform)
        ↓
[D-day + 31일] 삭제 증명서 발급
- 삭제 시각
- 삭제된 데이터 종류
- 책임자 서명
- 고객사 전달
```

### Legal Hold (법적 보관)

```
[발동 조건]
- 법적 분쟁 발생
- 규제 기관 요청
- 감사 진행 중

[처리]
1. 관리자가 retention_policies.legal_hold = true 설정
2. 자동 삭제 cron이 해당 데이터 건드림
3. legal_hold 해제 후 다시 자동 삭제

[관리자 API]
PUT /api/v1/admin/retention/{data_type}/legal-hold
body: { "enabled": true, "reason": "감사 진행 중" }
```

### 모니터링

```
[Prometheus 메트릭]
retention_deleted_rows_total{data_type="..."}
retention_cleanup_duration_seconds{job="..."}
retention_legal_hold_active{data_type="..."} 1=true, 0=false

[Grafana 대시보드]
- 일일 삭제 행 수 (데이터 종류별)
- 보존 정책 위반 (TTL 초과 데이터 잔존)
- Legal hold 활성 상태

[알람]
- cron 실패 (Critical)
- 보존 정책 위반 (Warning)
- 디스크 사용률 80% (Critical)
```

---

## 13. Phase별 도입 계획

### Phase 0 — MVP

```
☑ Open WebUI 자체 인증 + 관리자 승인 가입
☑ 비밀번호 정책 (최소 8자 + 영숫자)
☑ Spring Boot API Key (sk-rag-*, 1년 만료)
☑ Scope 기반 권한 (api:chat, api:admin, api:sync, api:config, api:audit, api:apikey)
☑ 관리자 API IP 화이트리스트
☑ Rate Limit (Redis, **API Key별 + 사용자별 이중**, 60/min)
☑ **X-User-Email/Id/Role 헤더 전달** (Open WebUI **백엔드(Python/FastAPI) 프록시**에서 주입)
☑ **사용자 헤더 보안 검증 (내부 IP만)**
☑ Audit Log (비동기, PII 마스킹, 1년 보존)
☑ Spring Security + bcrypt
☑ /v1/models 엔드포인트
☑ API Key 만료 임박 알람
☑ **`user_groups` / `group_definitions` 스키마 생성** (Phase 0엔 모든 사용자 `['all']` 부여, 검색 쿼리에 `access_groups && ARRAY['all']` 적용)
☑ **데이터 분류 가드** — `rag_table_config` / `sql_table_config` 등록 시 `'restricted'` 거부, `'internal'` admin 알람
```

### Phase 1 — 정식 출시

```
☑ API Key 자동 회전
☑ 비밀번호 만료 정책 (90일)
☑ 의심 활동 자동 차단 (인증 실패 다수)
☑ Audit Log 분석 대시보드 (Grafana)
☑ ~~관리자 Web UI~~ → **Phase 0 도입 완료 (ADR-0009)**. Phase 1+ 는 **인앱 튜토리얼·실시간 검증·SSE 진행 모니터링·Grafana 통합 대시보드 등 강화**만 추가
☑ NER 기반 PII 마스킹 (감사 로그)
☑ **데이터 접근 그룹 활성화** — 부서/팀 그룹 정의, user_groups에 부여, RAG/SQL 검색 쿼리 그룹 필터 ON
```

### Phase 2 — 확장

```
☑ SSO (OIDC, SAML, LDAP)
☑ MFA (Multi-Factor Authentication)
☑ 세션 관리 강화 (강제 로그아웃 등)
☑ CLI Tool 인증 (OAuth Device Flow)
☑ 감사 로그 외부 시스템 연동 (SIEM)
☑ 권한 그룹 (역할 기반 세분화)
```

---

## 14. DB 스키마 (인증 관련)

### 핵심 테이블

```sql
-- 1. API Key 관리
CREATE TABLE api_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL,
    key_hash        VARCHAR(255) NOT NULL UNIQUE,
    key_prefix      VARCHAR(20) NOT NULL,
    scopes          TEXT[] NOT NULL,
    is_active       BOOLEAN DEFAULT true,
    expires_at      TIMESTAMP NOT NULL,
    last_used_at    TIMESTAMP,
    created_by      VARCHAR(200),
    created_at      TIMESTAMP DEFAULT NOW(),
    deactivated_at  TIMESTAMP
);

CREATE INDEX idx_apikeys_prefix ON api_keys (key_prefix);
CREATE INDEX idx_apikeys_active ON api_keys (is_active, expires_at);

-- 2. 감사 로그 (이미 정의됨)
CREATE TABLE audit_logs (
    -- 위 9-1 참고
);

-- 3. 에러 로그 (06-error-handling.md에서 정의됨)
CREATE TABLE error_logs (
    -- ...
);

-- 4. 인증 실패 추적 (Phase 1+ 차단 정책용)
CREATE TABLE auth_failures (
    id              BIGSERIAL PRIMARY KEY,
    ip_address      VARCHAR(50),
    attempted_prefix VARCHAR(20),
    reason          VARCHAR(100),  -- 'invalid_key', 'expired', 'no_scope'
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_auth_failures_ip ON auth_failures (ip_address, created_at DESC);
```

### Open WebUI DB

```
별도 PostgreSQL 데이터베이스 (같은 RDS 인스턴스 내):
- 데이터베이스 이름: openwebui
- 우리는 직접 건드리지 않음
- 백업은 RDS 자동 백업으로 처리됨

[저장되는 정보]
- 사용자 계정
- 비밀번호 해시
- 세션
- 대화 히스토리
- 설정
- 파일 업로드 메타데이터
```

---

## 보안 체크리스트 (Phase 0 출시 전)

```
[인증]
☑ Open WebUI 자동 가입 비활성화 (관리자 승인)
☑ 초기 admin 계정 비밀번호 변경 완료
☑ API Key bcrypt 해시 저장 확인
☑ API Key 만료일 설정 (1년)

[권한]
☑ Scope 정의 및 엔드포인트 매핑
☑ 관리자 API IP 화이트리스트 적용
☑ ALB 보안 그룹 + Spring Boot 필터 이중 검증

[Rate Limit]
☑ Redis 카운터 동작 확인
☑ 429 응답 + Retry-After 헤더

[Audit]
☑ Audit Log 비동기 큐 동작 확인
☑ PII 마스킹 적용 확인
☑ 1년 보존 cron 동작 확인

[데이터 접근 정책 (옵션 D)]
☑ 고객사 admin과 합의서 체결 — "민감/기밀 데이터 RAG 등록 금지"
☑ rag_table_config / sql_table_config 등록 시 `data_sensitivity='restricted'` 거부 확인
☑ `'internal'` 등록 시 admin Discord 알람 동작 확인
☑ 모든 신규 사용자에게 `user_groups = ['all']` 자동 부여 확인
☑ 검색 쿼리에 `access_groups && $userGroups` 필터 항상 적용 확인

[비밀 관리]
☑ Secrets Manager 사용 (DB 비밀번호 등)
☑ application.yml에 평문 비밀번호 없음 확인
☑ Git 히스토리에 비밀 없음 확인

[네트워크]
☑ VPC 격리 (DB 외부 접근 불가)
☑ ALB HTTPS만 허용
☑ Spring Boot, k3s 내부 통신만
☑ 고객사 MySQL 연결은 VPC Peering/VPN
```
