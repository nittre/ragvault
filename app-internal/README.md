# app-internal (챗 서비스)

**사내 임직원용 챗 서비스**의 백엔드입니다. 클로드·ChatGPT 처럼 채팅 형태로 사내 문서·데이터베이스·웹을 검색해 근거 기반 답변을 제공합니다. 사내 datasource·PII 에 접근할 수 있는 **신뢰 영역** 애플리케이션입니다.

- 패키지 루트: `com.ragservice.rag`
- 포트: `8080` (컨테이너), 로컬 노출 `18090`
- 프론트엔드: [frontend/internal](../frontend/internal/README.md)

---

## 주요 기능

### 채팅 / 질의 파이프라인
- `controller/ChatController`, `controller/SearchController` — 채팅·검색 엔드포인트
- `service/RagService` — RAG 검색 + LLM 답변 + 출처 표기
- `service/HybridQueryService` — RAG · text-to-sql · 웹검색을 조합하는 하이브리드 질의 (타임아웃 `rag.hybrid.timeout-sec`)
- `service/IntentClassifierService`, `service/QueryRouterService` — 질의 의도 분류 및 라우팅
- `controller/ChatTitleController` — 대화 제목 자동 생성

### text-to-sql
- `service/TextToSqlService` — core 의 SqlGenerator/SqlValidator/SqlExecutor 를 조합
- `controller/SqlDownloadController` — 결과 행이 `rag.sql.csv-threshold`(50) 초과 시 CSV 다운로드
- 쿼리 타임아웃(기본 10초)·최대 행수(기본 1000행)는 정적 설정이 아니라 `admin_param_limits` 테이블 기반 동적 파라미터(M5 파라미터 튜닝 시스템, `ParameterResolver`)로 관리되며 요청/세션 단위 오버라이드가 가능

### 웹검색
- `service/WebSearchService` — 셀프호스팅 **SearXNG**(`rag.web-search.searxng-url`) 메타서치 연동
- `service/UrlFetchService` — readability4j + jsoup 로 URL 본문 추출

### 지식문서 / 파일 처리
- `service/KnowledgeDocIngestionService`, `controller/AdminKnowledgeController` / `AdminBusinessKnowledgeController` — 지식문서 적재(ADR-0004: SOURCE_TABLE 분리)
- `controller/FileController`, `service/FileProcessingService`, `service/FileContextService` — 파일 업로드·컨텍스트화
- `service/TesseractOcrService(Impl)`(core 모듈) — Tess4j OCR
- `service/ImagePathService` — `IMAGE`/`IMAGE_RAG` 질의 경로에서 qwen2.5-vl VLM 호출·이미지 분석
- `controller/FileController`, `service/FileProcessingService` — 파일 업로드 검증(30MB) → S3 저장 → Tika 텍스트 추출 → PII 마스킹 → 24시간 TTL 저장

### 외부 데이터소스 동기화 (아래는 모두 `core` 모듈 소속 — app-widget과 공유)
- `service/BinlogSyncService`(core), `domain/BinlogEvent`(core), `domain/BinlogPosition`(core) — MySQL binlog 실시간 동기화
- `service/InitialSyncService` — 초기 풀 스냅샷
- `service/WhitelistSyncService`(core), `domain/SyncJob`(core), `domain/SyncModeConfig`(core) — 동기화 화이트리스트·모드 관리
- `service/DataSourceAutoSetupService`(core) — 데이터소스 등록 시 SQL/RAG 테이블·PII 마스킹 자동 설정
- `service/DiscordNotifier`(core) — 동기화 이벤트 Discord 웹훅 알림

### 파라미터 튜닝 (M5)
- `service/ParameterResolver`, `ParameterValidator` — 전역 기본값(Stage 1) + 요청별/세션 오버라이드(Stage 2) + Guard A/B
- `domain/AdminParamLimit`
- `controller/UserParamController`(조회 전용), `AdminParamLimitsController`

### 어드민 콘솔 API
API Key(`AdminApiKeyController`), 감사 로그(`AdminAuditLogController`), 데이터소스(`AdminDataSourceController`, `AdminDdlController`), RAG/SQL 테이블(`AdminRagTableController`, `AdminSqlTableController`, `AdminDs*`), 마스킹 규칙(`AdminMaskingRuleController`), 스키마(`AdminSchemaController`), 동기화(`AdminSyncController`, `AdminSyncModeController`, `AdminRagSyncStatusController`), 사용 통계(`AdminUsageStatsController`), 사용자 관리(`AdminUserMgmtController`), SQL 로그(`AdminSqlLogController`) 등.

### 인증 / 보안
- `filter/JwtAuthFilter` — 자체 JWT 인증 (ADR-0011, `rag.auth.jwt-secret`)
- `filter/ApiKeyAuthFilter` — 서버 간 호출용 API Key
- `filter/TrustedHeaderFilter` — 신뢰 프록시 CIDR(`rag.security.trusted-proxy-cidrs`) 내에서만 `X-User-*` 헤더 신뢰
- `security/SsrfGuard`, `security/InputValidator` — SSRF 방어·입력 검증
- `controller/JwtAuthController` — 로그인/토큰 발급
- `runner/*` + `service/MailService` — 최초 슈퍼어드민·API Key 부트스트랩, 계정 발급/비밀번호 재설정 메일(Thymeleaf 템플릿)

---

## 기술 스택

| 항목 | 내용 |
|------|------|
| 프레임워크 | Spring Boot 3.5.0 (web, data-jpa, security, data-redis, actuator, mail, thymeleaf) |
| 공유 모듈 | `com.ragvault:core` |
| AI | Spring AI 1.0.0 `spring-ai-starter-model-ollama` |
| DB | PostgreSQL + pgvector, Flyway(V1~V40+ 마이그레이션) |
| 외부 DB | MySQL binlog-connector 0.29.2, mysql-connector-j 9.3.0, mariadb-java-client 3.5.2 |
| 분산 락 | ShedLock 5.16.0 (Redis provider) |
| 문서/OCR | Apache Tika 2.9.2, Tess4j 5.12.0, LangChain4j 0.36.2 |
| URL 처리 | readability4j 1.0.8, jsoup 1.18.3 |
| 스토리지 | AWS SDK v2 (S3) |
| SSH | Apache MINA SSHD 2.13.2, BouncyCastle 1.78.1 |
| 인증 | JJWT 0.12.6 |
| 관측 | Micrometer Prometheus, Spring Retry |

> Tika 2.9.2 의 `tika-parser-pdf-module` 은 exclude 되어 있으며, PDF 처리는 `core` 모듈의 `opendataloader-pdf-core`(PDFBox 3.x 기반)가 전담합니다 — ADR-0001.

---

## 아키텍처

```
com.ragservice.rag
├── controller/   REST API (Chat/Search/File + Admin* 다수)
├── service/      RAG·Hybrid·TextToSql·WebSearch·Sync·Parameter·File …
├── domain/       ApiKey, AuditLog, BusinessKnowledge, Binlog*, SyncJob, *Param* …
│   └── converter/  JPA 컨버터
├── repository/   Spring Data JPA
├── filter/       JwtAuthFilter, ApiKeyAuthFilter, TrustedHeaderFilter
├── security/     SsrfGuard, InputValidator
├── config/       SecurityConfig, AiConfig, AwsConfig, SyncConfig, AdminSpaConfig, Core*Config
├── runner/       기동 시 부트스트랩(슈퍼어드민·API Key)
└── dto/
```

### 요청 흐름 (RAG 채팅)
```
클라이언트 ─(JWT)→ JwtAuthFilter → ChatController → HybridQueryService
   ├── IntentClassifier → RagService (bge-m3 임베딩 → pgvector 검색)
   ├── TextToSqlService (SqlGenerator → SqlValidator → SqlExecutor)
   └── WebSearchService (SearXNG)
        → LLM(Ollama qwen2.5vl) 답변 + CitationSource
```

### 데이터
- **주 DB**: PostgreSQL `ragdb` (pgvector) — 벡터·업무 데이터. Flyway 로 스키마 관리
- **캐시/락**: Redis (ShedLock 으로 동기화 스케줄 중복 실행 방지)
- **외부 datasource**: MySQL/MariaDB — binlog 로 실시간 동기화, 접속정보는 AES-256-GCM 암호화 + SSH 터널

---

## 구동 / 설정

```bash
# 로컬 단독 실행 (인프라는 compose 로 별도 기동)
cd app-internal && ./gradlew bootRun    # SPRING_PROFILES_ACTIVE 기본 local

# 컨테이너 (레포 루트가 build context — core 포함)
docker build -f app-internal/Dockerfile -t app-internal:latest .
```

프로파일: `application-local.yml`(기본) / `application-dev.yml` / `application-prod.yml`.

주요 환경변수:

| 변수 | 설명 |
|------|------|
| `RAG_AUTH_JWT_SECRET` | JWT 시크릿 (≥32B, `openssl rand -hex 32`) |
| `RAG_DATASOURCE_ENC_KEY` | 데이터소스 암호화 키 (Base64 32B, `openssl rand -base64 32`) |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | pgvector 접속 |
| `SPRING_AI_OLLAMA_BASE_URL` | Ollama 엔드포인트 |
| `RAG_CHAT_MODEL` / `RAG_VLM_MODEL` | 채팅·비전 모델 (기본 `qwen2.5vl:7b`) |
| `RAG_SEARXNG_URL` | SearXNG URL |
| `BOOTSTRAP_SUPER_ADMIN_EMAIL` / `BOOTSTRAP_INITIAL_PASSWORD` / `BOOTSTRAP_API_KEY` | 최초 부트스트랩 |
| `RAG_S3_BUCKET` / `AWS_REGION` | 파일 임시 저장 |
| `DISCORD_WEBHOOK_URL` | 동기화 알림 |

액추에이터: `/actuator/health`, `/actuator/prometheus`. 헬스체크 경로 `/api/v1/health`.
