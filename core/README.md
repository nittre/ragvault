# core

ragvault 의 **공유 라이브러리 모듈**입니다. 챗 서비스(`app-internal`)와 위젯 서비스(`app-widget`) 양쪽이 공통으로 사용하는 도메인·서비스·정책을 담습니다. 실행 가능한 애플리케이션이 아니라 `java-library` 플러그인으로 빌드되는 순수 라이브러리이며, 각 앱이 `implementation 'com.ragvault:core'` 로 의존합니다(composite build).

패키지 루트: `com.ragvault.core`

---

## 주요 기능

### RAG / 임베딩
- `service/RoutingEmbeddingService` — 텍스트를 bge-m3 로 임베딩. 다중 소스 라우팅 지원
- `service/ChunkingService` — 문서를 청크로 분할(자체 구현 재귀 분할 — 구분자 우선순위 `"\n\n" > "\n" > ". " > " "`)
- `domain/DocumentChunk`, `repository/DocumentChunkRepository(+Custom/Impl)` — pgvector 코사인 유사도 검색 커스텀 쿼리
- `domain/RoutingEmbedding`, `repository/RoutingEmbeddingRepository(+Custom/Impl)` — 임베딩 저장/검색
- `dto/CitationSource` — 답변 출처 표기 DTO

### text-to-sql
- `service/SqlGeneratorService` — LLM 으로 자연어 → SQL 생성
- `service/SqlValidator` — JSqlParser(4.9) AST 기반 SQL 안전성 검증(읽기 전용, 화이트리스트)
- `service/SqlExecutorService` — 검증된 SQL 실행
- `service/SchemaInspectorService` / `SchemaDescriptionService` — 스키마 추출 및 LLM 용 스키마 설명 생성(`@Async`)
- `service/RagColumnSuggestionService` — RAG 대상 컬럼 자동 제안(`@Async`)
- `service/DataSourceRouterService` / `QueryIntent` — 질의 의도에 따른 데이터소스 라우팅
- `domain/SqlTableConfig`, `SqlColumnDescription`, `RagTableConfig`, `SqlExecutionLog` 및 각 Repository

### 문서 파싱 (`service/parser`) — ADR-0001
- `DocumentParser` (인터페이스) / `ParsedDocument` — 파서 계약과 결과 모델
- `DocumentParserRouter` — 파일 타입별 파서 라우팅
- `TikaDocumentParser` — Office/CSV/TXT → 텍스트 + 임베디드 이미지(Apache Tika + POI)
- `OpenDataLoaderPdfParser` — PDF → Markdown(표 구조 보존) + 이미지(opendataloader-pdf)
- `MarkdownPassThroughParser` — Markdown 원본 통과

### 이미지 처리 — ADR-0002
- `service/ImageCaptioningService` — 비전 모델(qwen2.5vl)로 이미지 캡션 생성 → 텍스트 임베딩

### 데이터소스 보안
- `service/DataSourceEncryptionService` — 외부 DB 비밀번호 AES-256-GCM 암호화
- `service/DataSourceConfigService`, `domain/DataSourceConfig`, `repository/DataSourceConfigRepository` — 데이터소스 설정 관리
- `service/SshTunnelService` — Bastion 경유 SSH 터널(Apache MINA SSHD + BouncyCastle)

### 인증 / 보안
- `service/JwtService` — JWT 발급·검증(JJWT 0.12.6)
- `service/RagUserService`, `domain/RagUser`, `domain/RagRole` — 사용자·역할, BCrypt 비밀번호
- `security/PiiMasker`, `domain/MaskingRule`, `repository/MaskingRuleRepository` — PII 마스킹 규칙
- `security/Auditable` — 감사 로그 자동 기록 마커 어노테이션(SpEL 기반). app-internal/app-widget이 각자 구현한 `AuditLogAspect`가 이 어노테이션이 붙은 메서드를 처리한다(개발자 매뉴얼 6-3 참고)
- `service/SensitivityAnalysisService` — 민감도 분석(`@Async`). 테이블 `data_sensitivity` 라벨 산정에 사용
- `dto/LoginRequest`, `LoginResponse`, `dto/DataSourceRequest`

### 검색 설정
- `domain/SearchConfig`, `repository/SearchConfigRepository` — top-k / threshold 등 검색 파라미터

### 외부 데이터소스 동기화
- `service/BinlogSyncService` — MySQL binlog 기반 실시간 동기화, DDL 위험도 분류(`classifyDdlRisk`)
- `service/DataSourceAutoSetupService` — 신규 데이터소스 자동 초기 설정(`@Async`)
- `service/WhitelistSyncService` — RAG/SQL 대상 테이블 화이트리스트 동기화
- `service/RagTableConfigService` — RAG 테이블 설정 관리
- `domain/BinlogEvent`, `BinlogPosition`, `DdlEvent`, `SyncJob`, `SyncModeConfig`, `WebSearchExecutionLog` 및 각 Repository

### OCR · 알림
- `service/TesseractOcrService`/`TesseractOcrServiceImpl` — Tess4j 기반 OCR (`kor+eng`)
- `service/parser/PdfOcrFallbackService` — PDF 텍스트 추출 실패 시 OCR 폴백 — ADR-0006
- `service/DiscordNotifier` — Discord 웹훅 알림

### 프롬프트 · 유틸
- `prompt/PromptLoader` — classpath 프롬프트 템플릿 로더
- `util/DailyCountFiller` — 통계용 일자별 카운트 채움 유틸

---

## 기술 스택

| 항목 | 내용 |
|------|------|
| 빌드 | Gradle `java-library`, Java 21 toolchain |
| 프레임워크 | Spring Boot 3.5.0 BOM, Spring AI 1.0.0 BOM |
| AI | `spring-ai-starter-model-ollama` (ChatClient + Embedding) |
| 데이터 | Spring Data JPA, Jackson |
| 인증 | Spring Security(BCrypt), JJWT 0.12.6 |
| SQL 파싱 | JSqlParser 4.9 |
| 문서 파싱 | Apache Tika 2.9.2, opendataloader-pdf-core 1.11.0 |
| SSH | Apache MINA SSHD 2.13.2, BouncyCastle 1.78.1 |
| OCR | Tess4j 5.12.0 |
| 외부 DB 동기화 | mysql-binlog-connector-java 0.29.2 |
| 분산 락 | shedlock-spring 5.16.0 |
| 유틸 | Lombok |

> opendataloader-pdf 의 verapdf 의존성 때문에 `artifactory.openpreservation.org` 저장소를 추가로 사용합니다.

---

## 아키텍처

```
com.ragvault.core
├── domain/        JPA 엔티티 (RagUser, DocumentChunk, RoutingEmbedding,
│                  SqlTableConfig, RagTableConfig, DataSourceConfig, MaskingRule,
│                  BinlogEvent, BinlogPosition, DdlEvent, SyncJob, SyncModeConfig …)
├── repository/    Spring Data JPA + Custom/Impl (pgvector 벡터 검색 커스텀 쿼리)
├── dto/           요청·응답 DTO (Login, DataSource, CitationSource)
├── prompt/        classpath 프롬프트 템플릿 로더(PromptLoader)
├── service/       핵심 비즈니스 로직 (임베딩·SQL·파싱·암호화·JWT·사용자·binlog 동기화·OCR·알림)
│   └── parser/    멀티포맷 문서 파서 계층 (Router → Tika/PDF/Markdown, OCR 폴백)
├── security/      PiiMasker, Auditable(감사 로그 자동 기록 마커 어노테이션)
└── util/          DailyCountFiller
```

- **소비 관계**: `app-internal` / `app-widget` 이 core 의 service·domain 을 주입받아 사용합니다. 각 앱은 `CorePackageConfig` / `CoreServicesConfig` 로 core 패키지의 빈을 스캔합니다.
- **DB 비의존**: core 는 특정 DB 에 묶이지 않고, 실제 datasource·pgvector 연결과 프로파일 설정은 각 앱이 담당합니다.

---

## 빌드

독립 실행되지 않으며, 각 앱 빌드 시 composite build 로 함께 컴파일됩니다.

```bash
cd core && ./gradlew build     # 단독 컴파일/검증
```
