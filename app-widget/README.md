# app-widget (위젯 서비스)

**외부 고객사 웹사이트에 임베드되는 위젯 챗봇**의 백엔드입니다. 고객사가 `<script>` 한 줄로 삽입한 채팅 버블이 이 서버와 통신합니다. 지식문서(멀티포맷) 기반 RAG 로 고객 문의에 답하고, 고객사 소유 데이터베이스에 한해 text-to-sql 을 허용합니다.

> **보안 불변식**: 위젯 서비스는 **사내 datasource·PII 에 절대 접근하지 않습니다.** 이 경계는 아키텍처로 집행됩니다 — 위젯은 챗 서비스와 별개 앱이며 자신의 `widget_db` 만 바라봐서 사내 데이터로 가는 경로 자체가 없습니다. 추가로 시스템 프롬프트에서도 사내 DB/SQL/개인정보 질문을 거부하도록 지시합니다.

- 패키지 루트: `com.ragvault.widget`
- 포트: `8081`
- 프론트엔드: 어드민 [frontend/widget-admin](../frontend/widget-admin/README.md), 임베드 [frontend/widget-embed](../frontend/widget-embed/README.md)

---

## 주요 기능

### 위젯 채팅 (공개 API)
- `controller/WidgetChatController` — 방문자 채팅 엔드포인트 (Site-Key 인증)
- `controller/WidgetConfigController` — 위젯 표시 설정 조회
- `service/WidgetRagService` — 지식베이스 RAG 검색 + LLM 답변 + 출처 표기
- `service/QueryRouterService`, `service/IntentClassifierService` — 질의 라우팅/의도 분류
- `service/ConversationLogService`, `domain/ConversationLog`, `controller/ConversationLogController` — 대화 로그 적재/조회

### 지식문서 관리
- `service/KnowledgeIngestionService`, `controller/KnowledgeAdminController` — 멀티포맷 문서 업로드·파싱·임베딩(core 파서 재사용)
- 이미지 캡셔닝/벡터화 — 비전 모델(`widget.knowledge.vision-model`, 기본 `qwen2.5vl:7b`) (ADR-0002)
- `widget.knowledge.auto-load=true` 시 `knowledge/` 디렉토리 문서를 기동 시 자동 적재
- 저장소 디렉토리: [`knowledge/`](knowledge) (운영 적재 대상), [`faq/`](faq) (초기 예시 FAQ 마크다운: 배송/반품/계정)

### text-to-sql (고객 datasource)
- `service/TextToSqlService` — 고객사 DB 대상 자연어 → SQL (core SqlValidator 로 AST 검증)
- `service/DsSyncService`, `service/InitialSyncService`, `domain/DsSyncJob`, `domain/DsRagTable` — 외부 datasource 동기화
- `controller/AdminDataSourceController`, `AdminDsRagTableController`, `AdminDsSqlTableController`, `AdminSchemaController`, `AdminQueryController` — 데이터소스·RAG/SQL 테이블·스키마·쿼리 콘솔

### 어드민
- `controller/SiteKeyController`, `service/SiteKeyService`, `domain/SiteKey` — Site-Key 발급/관리
- `controller/SearchConfigController`, `service/SearchConfigService` — 검색 파라미터(top-k/threshold)
- `controller/MaskingRuleController`, `service/MaskingRuleService` — PII 마스킹 규칙
- `controller/AuditLogController`, `service/AuditLogService`, `domain/AuditLog` — 감사 로그
- `controller/AdminUserMgmtController`, `AdminMeController`, `JwtAuthController` — 사용자·인증
- `service/WhitelistSyncService`

### 인증 / 보안
- `filter/SiteKeyFilter` — 공개 위젯 API 는 `X-Site-Key` 헤더 검증(`widget.security.allowed-site-keys` 화이트리스트)
- `filter/JwtAuthFilter` — 어드민 API 는 JWT(httpOnly 쿠키) 인증
- `config/SecurityConfig` — 위젯용 CORS(credentials=false, 고객 도메인 화이트리스트) + 어드민용 CORS(credentials=true)
- `security/InputValidator` — 입력·프롬프트 인젝션 방어

---

## 기술 스택

| 항목 | 내용 |
|------|------|
| 프레임워크 | Spring Boot 3.5.0 (web, data-jpa, security, actuator) |
| 공유 모듈 | `com.ragvault:core` |
| AI | Spring AI 1.0.0 `spring-ai-starter-model-ollama` |
| DB | PostgreSQL + pgvector (`widget_db`), Flyway(V1~V12) |
| 외부 DB | mysql-connector-j (고객 datasource 동기화) |
| SQL 검증 | JSqlParser 4.9 |
| 인증 | JJWT 0.12.6 |
| 유틸 | Lombok |

문서 파싱(Tika, opendataloader-pdf) 및 이미지 캡셔닝·암호화·SSH 터널은 `core` 모듈을 통해 제공됩니다.

---

## 아키텍처

```
com.ragvault.widget
├── controller/   WidgetChat/WidgetConfig(공개) + Admin*·SiteKey·Knowledge(어드민)
├── service/      WidgetRag·TextToSql·KnowledgeIngestion·DsSync·SiteKey·SearchConfig …
├── domain/       SiteKey, ConversationLog, AuditLog, DsSyncJob, DsRagTable
├── repository/   Spring Data JPA
├── filter/       SiteKeyFilter(공개), JwtAuthFilter(어드민)
├── security/     InputValidator
├── config/       SecurityConfig, WebConfig, AiConfig, AsyncConfig, CacheConfig, Core*Config
├── runner/       기동 시 부트스트랩(슈퍼어드민 계정)
└── dto/
```

### 요청 흐름
```
[방문자] ─(X-Site-Key)→ SiteKeyFilter → WidgetChatController → WidgetRagService
                                              → pgvector(widget_db) 검색 → LLM 답변 + 출처
[고객 어드민] ─(JWT 쿠키)→ JwtAuthFilter → Admin*Controller
```

- **DB 격리**: 챗 서비스와 동일 pgvector 인스턴스를 쓰되 **별도 DB `widget_db`** 를 사용해 벡터가 섞이지 않습니다(ADR-0004).

---

## 구동 / 설정

```bash
cd app-widget && ./gradlew bootRun

# 컨테이너 (레포 루트가 build context — core 포함)
docker build -f app-widget/Dockerfile -t app-widget:latest .
```

주요 환경변수:

| 변수 | 설명 |
|------|------|
| `WIDGET_DB_URL/USERNAME/PASSWORD` | pgvector `widget_db` 접속 |
| `OLLAMA_BASE_URL` | Ollama 엔드포인트 |
| `WIDGET_AUTH_JWT_SECRET` | 어드민 JWT 시크릿 (≥32B) |
| `WIDGET_ENCRYPTION_KEY` | 데이터소스 암호화 키 (Base64 32B) |
| `WIDGET_ALLOWED_SITE_KEYS` | 허용 Site-Key 콤마 목록 |
| `WIDGET_CORS_ORIGINS` | 위젯 삽입 고객 도메인 화이트리스트 |
| `WIDGET_ADMIN_CORS_ORIGINS` | 어드민 SPA origin (credentials 허용) |
| `WIDGET_CHAT_MODEL` / `WIDGET_VISION_MODEL` | 채팅·비전 모델 |
| `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` | 최초 관리자 계정 |

헬스체크: `/actuator/health`.
