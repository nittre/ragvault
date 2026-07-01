# ragvault

**사내 챗 서비스**(`app-internal`)와 **외부 임베드 위젯 서비스**(`app-widget`)를 **하나의 코드베이스**로 통합한 RAG 플랫폼 모노레포입니다.
공유 `core` 모듈 위에 제품별 백엔드 앱과 프론트엔드를 얹은 **모듈러 모놀리스 / 제품 라인** 구조를 따릅니다.

> **용어 규칙**
> - **챗 서비스** (`app-internal`) — 사내 임직원용. 클로드·ChatGPT 처럼 채팅 형태로 사내 문서·DB·웹을 검색해 답변하는 서비스.
> - **위젯 서비스** (`app-widget`) — 외부 고객사 웹사이트에 `<script>` 한 줄로 삽입되는 임베드 챗봇 서비스.

---

## 이 서비스는 무엇인가

두 제품 모두 **RAG(Retrieval-Augmented Generation)** 를 핵심으로 합니다. 사용자의 질문을 임베딩해 벡터 검색으로 근거 문서를 찾고, 로컬 LLM(Ollama)이 그 근거만 사용해 답변합니다. 여기에 제품별로 아래 기능이 얹힙니다.

| 구분 | 챗 서비스 (app-internal) | 위젯 서비스 (app-widget) |
|------|--------------------------|--------------------------|
| 대상 | 사내 임직원 | 외부 고객사 방문자 |
| 인증 | JWT + API Key + 신뢰 프록시 헤더 | Site-Key(`X-Site-Key`) + JWT(어드민) |
| 데이터 경계 | 사내 datasource·PII 접근 허용 | **사내 데이터에 절대 접근 불가**, 고객사 datasource만 |
| 주요 기능 | RAG · text-to-sql · 웹검색 하이브리드 · 파일 처리(OCR) | RAG · 지식문서 관리 · text-to-sql(고객 DB) |
| 백엔드 포트 | 8080 | 8081 |

### 핵심 보안 불변식
> 외부 위젯(`app-widget`)은 **사내 datasource·PII 에 절대 닿지 않는다.**
>
> 이 경계는 라벨/정책 코드가 아니라 **아키텍처로 집행된다** — 챗과 위젯은 별개 애플리케이션이며 각자 별개 DB(`ragdb` / `widget_db`)만 바라본다. 위젯에서 사내 데이터로 가는 접근 경로 자체가 존재하지 않는다. 서비스 내부의 세밀한 노출 통제는 **테이블/컬럼 단위**(RAG/SQL 대상 테이블 활성화, PII 마스킹 규칙, `data_sensitivity='restricted'` 등록 거부)가 담당한다.

---

## 핵심 기능

- **RAG 채팅** — bge-m3 임베딩 + pgvector 코사인 검색 → LLM 답변 + 출처(Citation) 표기
- **하이브리드 질의(챗)** — 의도 분류 후 RAG / text-to-sql / 웹검색을 조합 (`HybridQueryService`)
- **text-to-sql** — 자연어 → SQL 생성(`SqlGeneratorService`) → JSqlParser AST 검증(`SqlValidator`) → 읽기 전용 실행 → CSV 다운로드
- **웹검색(챗)** — 셀프호스팅 메타서치 엔진 **SearXNG** 연동 (`WebSearchService`)
- **멀티포맷 문서 파싱** — Apache Tika(Office/CSV/TXT) + opendataloader-pdf(PDF→Markdown, 표 구조 보존) — ADR-0001
- **이미지 벡터화** — 비전 모델(qwen2.5vl) 캡셔닝 후 텍스트 임베딩 — ADR-0002
- **외부 데이터소스 연동** — AES-256-GCM 암호화 저장 + SSH 터널(Bastion) 접속, MySQL binlog 실시간 동기화
- **어드민 콘솔** — 사용자·API Key·데이터소스·RAG/SQL 테이블·마스킹 규칙·감사 로그·사용 통계·파라미터 튜닝

---

## 레포 구성

```
ragvault/
├── settings.gradle          # Gradle composite build (includeBuild)
├── core/                    # 공유 모듈: RAG·text-to-sql·문서파싱·임베딩·암호화·JWT
├── app-internal/            # 챗 서비스 백엔드 (Spring Boot, :8080)  — com.ragservice.rag
├── app-widget/              # 위젯 서비스 백엔드 (Spring Boot, :8081) — com.ragvault.widget
├── frontend/
│   ├── internal/            # 챗 서비스 프론트 (채팅 UI + 어드민 통합 SPA)
│   ├── widget-admin/        # 위젯 서비스 어드민 SPA
│   └── widget-embed/        # 위젯 임베드 자산 (loader.js / chat.html / demo.html)
├── infra/                   # Docker Compose (base + 제품별 overlay + 개발서버)
├── jenkins/                 # CI/CD 파이프라인 4종
└── docs/adr/                # 아키텍처 결정 기록(ADR)
```

각 하위 모듈에는 상세 README가 있습니다 →
[core](core/README.md) ·
[app-internal](app-internal/README.md) ·
[app-widget](app-widget/README.md) ·
[frontend/internal](frontend/internal/README.md) ·
[frontend/widget-admin](frontend/widget-admin/README.md) ·
[frontend/widget-embed](frontend/widget-embed/README.md) ·
[infra](infra/README.md)

---

## 기술 스택

| 레이어 | 기술 |
|--------|------|
| 언어/런타임 | Java 21, TypeScript 5.6, Node 22 |
| 백엔드 | Spring Boot 3.5.0, Spring AI 1.0.0(Ollama), Spring Security, Spring Data JPA, Flyway |
| LLM/임베딩 | Ollama — 채팅/비전 `qwen2.5vl:7b`(운영), 임베딩 `bge-m3` |
| 벡터 DB | PostgreSQL 16 + pgvector |
| 문서 처리 | Apache Tika 2.9.2, opendataloader-pdf 1.11.0, Tess4j(OCR) |
| 외부 DB | MySQL(binlog-connector), MariaDB — 데이터소스 동기화 |
| 캐시/락 | Redis 7 (ShedLock 분산 스케줄 락) |
| 웹검색 | SearXNG |
| 프론트엔드 | React 18, Vite 6, Tailwind 3, React Query, Zustand, React Router |
| 빌드 | Gradle (composite build) |
| 인프라 | Docker Compose, Nginx |
| CI/CD | Jenkins (SSH 기반 이미지 전송·배포) |

---

## 서비스 구동 방법

### 사전 준비 (공통)

```bash
# 외부 도커 네트워크 (한 번만)
docker network create rag-net

# 환경변수 (openssl 로 시크릿 생성)
export RAG_AUTH_JWT_SECRET=$(openssl rand -hex 32)      # 챗 JWT
export RAG_DATASOURCE_ENC_KEY=$(openssl rand -base64 32) # 챗 데이터소스 암호화
export WIDGET_AUTH_JWT_SECRET=$(openssl rand -hex 32)    # 위젯 JWT
export WIDGET_ENCRYPTION_KEY=$(openssl rand -base64 32)  # 위젯 데이터소스 암호화
```

로컬 개발용 기본값은 루트 `.env` 를 참고하세요 (커밋 금지).

### 로컬 개발 환경 (Docker Compose)

`compose.base.yml`(ollama + pgvector 공용) 위에 제품별 overlay 를 얹어 실행합니다. overlay 에 `build:` 섹션이 있어 **로컬에서 이미지를 직접 빌드**합니다.

```bash
# 챗 서비스 (app-internal + 프론트 + mysql/mariadb 샘플 + searxng + redis)
docker compose -f infra/compose.base.yml -f infra/compose.internal.yml up -d --build

# 위젯 서비스 (app-widget + 어드민 + 데모 + shop-mariadb)
docker compose -f infra/compose.base.yml -f infra/compose.widget.yml up -d --build
```

| 서비스 | URL |
|--------|-----|
| 챗 프론트엔드 | http://localhost:18080 |
| 챗 백엔드 API | http://localhost:18090 |
| 위젯 어드민 | http://localhost:5173 |
| 위젯 임베드 데모 | http://localhost:8080/demo.html |
| 위젯 백엔드 API | http://localhost:8081 |

백엔드만 IDE 에서 직접 실행할 수도 있습니다:

```bash
cd app-internal && ./gradlew bootRun    # 챗
cd app-widget   && ./gradlew bootRun    # 위젯
```

### 개발 서버 환경 (Jenkins 배포)

개발 서버에서는 **직접 빌드하지 않습니다.** Jenkins 가 이미지를 빌드해 `docker save | ssh docker load` 로 전송하고, `build:` 섹션이 없는 `compose.dev.yml` 로 컨테이너만 교체합니다.

```bash
# 개발 서버에서 전체 스택 기동 (Jenkins 가 이미지를 미리 적재한 상태)
docker compose -f infra/compose.dev.yml --env-file infra/.env.dev up -d
```

Jenkins 파이프라인(수동 트리거)은 아래 4종입니다. 공통 흐름은
**Checkout → Build Image → Sync Configs(tar/ssh) → Transfer Image(docker save→load) → Deploy(`up -d --no-deps --force-recreate`) → Verify** 입니다.

| Jenkinsfile | 대상 모듈 | 이미지 |
|-------------|-----------|--------|
| `jenkins/Jenkinsfile.chat-backend` | app-internal | `app-internal:latest` |
| `jenkins/Jenkinsfile.chat-frontend` | frontend/internal | `rag-frontend:latest` |
| `jenkins/Jenkinsfile.widget-backend` | app-widget | `app-widget:latest` |
| `jenkins/Jenkinsfile.widget-frontend` | frontend/widget-admin | `widget-admin:latest` |

---

## 인프라 아키텍처

```
                        ┌──────────────────────────────────────────────┐
                        │                  rag-net (docker)             │
                        │                                              │
  [사내 사용자] ──────► rag-frontend(:18080) ──► app-internal(:8080) ──┐│
                        │                          │  │  │             ││
  [고객사 방문자] ────► loader.js/iframe ──► app-widget(:8081) ──┐    ││
                        │  widget-admin(:5173)      │            │    ││
                        │                           ▼            ▼    ▼│
                        │                    ┌──────────────────────────┐
                        │  ┌── ollama(:11434) ── qwen2.5vl / bge-m3      │
                        │  ├── pgvector(pg16) ── ragdb + widget_db       │
                        │  ├── redis ─────────── ShedLock (챗)           │
                        │  ├── searxng ───────── 웹검색 (챗)             │
                        │  ├── mysql-sample ──── binlog 동기화 (챗)      │
                        │  ├── mariadb-board ─── 외부 datasource (챗)    │
                        │  └── shop-mariadb ──── 고객 datasource (위젯)  │
                        └──────────────────────────────────────────────┘
```

- **공용 자원**: `ollama`(LLM/임베딩/비전), `pgvector`(벡터 DB — `ragdb`는 챗, `widget_db`는 위젯). `pg-init` 스크립트가 최초 기동 시 `widget_db`·`widget` 유저를 자동 생성합니다.
- **데이터 격리**: 두 서비스가 동일 pgvector 인스턴스를 공유하지만 DB(스키마)를 분리하고, 챗의 지식문서는 SOURCE_TABLE 로 벡터를 분리합니다(ADR-0004).
- **네트워크**: 모든 서비스가 외부 네트워크 `rag-net` 에 참여합니다.

자세한 서비스·포트·볼륨 정의는 [infra/README.md](infra/README.md) 참고.

---

## 문서 (ADR)

중요한 아키텍처·정책 결정은 [docs/adr](docs/adr/README.md) 에 불변(immutable) 기록으로 관리합니다.

| # | 제목 |
|---|------|
| [0001](docs/adr/0001-multiformat-document-parser.md) | 멀티포맷 문서 파서 — Apache Tika + opendataloader-pdf |
| [0002](docs/adr/0002-image-captioning-vectorization.md) | 이미지 벡터화 — 비전 모델 캡셔닝 후 텍스트 임베딩 |
| [0003](docs/adr/0003-frontend-monorepo-integration.md) | 프론트엔드 모노레포 통합 |
| [0004](docs/adr/0004-chat-service-knowledge-docs.md) | 챗 서비스 지식문서 관리 — SOURCE_TABLE 분리 |
| [0005](docs/adr/0005-qwen25vl-unified-model.md) | qwen2.5vl:7b 단일 멀티모달 모델 통합 |
