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

## 질의 파이프라인

챗 서비스(`app-internal`) 기준. 코드 상 진입점은 `ChatController` → `QueryRouterService` → `IntentClassifierService`(의도 분류) 순이며, 의도별로 아래 서비스 중 하나(또는 조합)로 분기한다.

### 1. 전체 흐름

```
클라이언트 ─(JWT)→ ChatController(/v1/chat/completions)
                       │
                       ▼
              QueryRouterService.route()
                       │
       ┌───────────────┴───────────────┐
       │ 메시지가 /rag, /web, /sql 로   │  없음 →  IntentClassifierService.classify()
       │ 시작하거나 routingHint 있음    │            Stage1(정적 규칙, LLM 미호출):
       │ → FORCE_RAG/FORCE_WEB/FORCE_SQL│              IMAGE > FILE > URL_FETCH
       └───────────────┬───────────────┘            Stage2(LLM 분류, Redis 24h 캐시):
                       │                               RAG / SQL / HYBRID / WEB_SEARCH / REJECT
                       ▼
   RagService · TextToSqlService · HybridQueryService · WebSearchService ·
   UrlFetchService · FileContextService · ImagePathService 중 실행
                       │
                       ▼
       ResponseRawStorageService 원본 저장(ADR-0010) → PiiMasker 마스킹(ADR-0008)
                       │
                       ▼
                ChatCompletionResponse
```

### 2. 질의 유형 판별 (라우팅)

`IntentClassifierService`가 9가지 의도로 분류한다.

| 단계 | 의도 | 판별 방식 |
|------|------|-----------|
| Stage 1 (정적 규칙, LLM 미호출, 우선순위순) | `IMAGE` / `IMAGE_RAG` | 이미지 첨부 있음 |
| | `FILE` | `fileIds` 있음 |
| | `URL_FETCH` | 메시지에 `http(s)://` 포함 |
| Stage 2 (LLM 분류, Redis 24h 캐시 — 캐시 키에 질문 + 활성 datasource fingerprint 반영) | `RAG` | 문서/매뉴얼 개념·설명 질문 |
| | `SQL` | 특정 레코드·수치 조회(명단, 총액, 몇 명 등) |
| | `HYBRID` | 수치 조회 + 설명이 동시에 필요 |
| | `WEB_SEARCH` | 내부 데이터에 없는 최신·외부 정보 (`rag.web-search.enabled=true`일 때만) |
| | `REJECT` | 프롬프트 인젝션·시스템 조작 시도 (애매하면 REJECT 대신 RAG로 분류하도록 프롬프트에 명시) |

`IMAGE`와 이미지+텍스트가 함께 온 경우의 `IMAGE_RAG`(2-Phase)만 예외적으로 정적 규칙 다음 별도 분기를 탄다 (9번 항목 참고). LLM 분류 호출 자체가 실패하면 `RAG`로 간주한다.

### 3. 명시적 우선 사용 (`/rag`, `/web`, `/sql`)

사용자가 메시지를 `/rag `, `/web `, `/sql `로 시작하면(또는 프론트엔드가 `routingHint`를 직접 지정하면) `IntentClassifierService` 분류를 건너뛰고 강제 라우팅한다. 메시지 자체에서도 파싱하는 이유는 프론트엔드 미지원 클라이언트·브라우저 캐시 대응이다.

| 커맨드 | 동작 | 폴백 |
|--------|------|------|
| `/rag` | RAG 우선 실행 | 검색 결과 없으면 → `WEB_SEARCH` |
| `/web` | 웹검색(SearXNG) 우선 실행 | 거부(denied)되면 → `RAG` |
| `/sql` | text-to-sql만 실행 | 폴백 없음 |

### 4. 하이브리드 질의 (`HybridQueryService`)

RAG와 SQL을 `CompletableFuture.allOf()`로 **병렬 실행**하고(웹검색은 `rag.web-search.hybrid-enabled=false`가 기본값이라 보통 제외), `rag.hybrid.timeout-sec`(기본 120초) 내에 끝난 결과만 모아 LLM으로 종합한다. 타임아웃이나 개별 실행 오류가 나도 완료된 것만 부분 결과로 사용하고 요청 자체를 실패시키지 않는다.

종합 프롬프트는 결과를 `[문서 검색 결과]` / `[데이터베이스 조회 결과]` / `[웹 검색 결과]`로 구획해 전달하며, "분류 체계·구체적 항목(대주제·하위주제 등)은 반드시 DB 조회 결과만 근거로 삼고 문서 검색 결과는 개념 보충 설명에만 사용하라"고 명시해 LLM이 DB에 없는 항목을 지어내는 것을 막는다.

### 5. 폴백 로직 정리

| 상황 | 폴백 |
|------|------|
| 자동 분류 결과 `RAG`인데 검색 결과 없음 | `WEB_SEARCH` (`rag.web-search.enabled=true`일 때만) |
| `/rag` 강제인데 검색 결과 없음 | `WEB_SEARCH` |
| `/web` 강제인데 결과가 거부(denied)됨 | `RAG` |
| `IMAGE_RAG` 2단계 재분류가 `HYBRID`인데 SQL이 거부되거나 0행 | `RAG` |
| Intent 분류 LLM 호출 자체가 예외 발생 | `RAG` 취급 |
| 후속 질문 재작성(`rewriteStandaloneQuery`) LLM 호출 실패 | 원본 질문 그대로 검색 (fail-open) |
| 지식문서 PDF 텍스트 추출 사실상 실패 | Tesseract OCR로 복구 시도 (8번 항목) |

### 6. 멀티턴(대화 맥락) 처리

`RagService.chat()`은 대화 이력이 있으면 `rewriteStandaloneQuery()`로 "더 자세히" 같은 후속 질문을 이력 맥락을 반영한 독립형 질문으로 재작성한 뒤, **그 재작성된 질문으로 임베딩·pgvector 검색**을 수행한다(단, 최종 LLM 프롬프트의 `[현재 질문]`에는 사용자가 실제로 입력한 원본 메시지를 그대로 사용). 재작성 LLM 호출이 실패하면 원본 질문으로 폴백한다.

후속 질문은 첫 턴과 동일한 청크만 다시 뽑혀 답변이 늘어나지 않는 문제가 있었기 때문에, `topK`를 첫 턴(기본 5) 대비 2배(최대 20)로 늘려 더 많은 근거 청크를 확보한다.

### 7. 파일 업로드

채팅에 파일을 첨부하는 `FILE` 경로는 **업로드 시점**과 **질의 시점**이 분리되어 있다.

- 업로드 시(`FileProcessingService`): 검증(30MB, 허용 확장자만) → S3 저장 → Apache Tika `AutoDetectParser`로 텍스트 추출 → PII 마스킹 → 24시간 TTL로 DB 저장.
- 질의 시(`FileContextService`): 이미 추출된 텍스트를 프롬프트에 그대로 넣어 LLM 호출만 수행.

> 이 경로는 Tika 추출 실패 시 빈 문자열로 폴백할 뿐 OCR 폴백이 없다. OCR은 아래 8번의 **지식문서 적재(RAG 인덱스 구축)** 파이프라인에만 있다 — 두 파이프라인을 혼동하지 않도록 구분.

### 8. 지식문서 적재 · OCR

RAG가 검색하는 벡터 인덱스 자체를 채우는 관리자 파이프라인(위 7번의 채팅 첨부파일과는 별개)이다.

- Office(docx/xlsx/pptx 등)는 Apache Tika, PDF는 `OpenDataLoaderPdfParser`(opendataloader-pdf, PDF→Markdown, 표 구조 보존)로 변환한다 — ADR-0001.
- PDF 텍스트가 폰트가 아닌 벡터 윤곽선으로만 그려져 있는 등의 이유로 추출이 사실상 실패(이미지 마크다운 제외 순수 텍스트 20자 미만, 또는 페이지당 평균 30자 미만 & 전체 500자 미만)하면 `PdfOcrFallbackService`가 개입한다 — ADR-0006.
  - PDFBox `PDFRenderer`로 페이지를 200 DPI 이미지로 렌더링 → Tesseract(`kor+eng`, OEM=1 LSTM 전용 고정)로 페이지별 OCR → 마크다운으로 합침.
  - 페이지 상한 20장, 전체 타임아웃 180초. 실패해도 원본(빈약한) 마크다운으로 관대하게 폴백해 업로드 자체는 막지 않는다.
  - Alpine 배포 환경에서 Tesseract 기본 엔진(OEM 자동)이 한글을 음절 단위로 쪼개 인식하는 결함이 있어 OEM을 1로 명시 고정했다(ADR-0006 참고).
- 이미지(비전 모델 캡셔닝 → 텍스트 임베딩)는 별도 경로다 — ADR-0002.

### 9. 이미지 질의 (`IMAGE` / `IMAGE_RAG`)

- `IMAGE`: 이미지 자체를 분석·설명하는 것이 질문의 전부일 때. `qwen2.5-vl` VLM이 직접 답변.
- `IMAGE_RAG`(2-Phase): 이미지와 함께 내부 DB/문서/웹 검색도 필요할 때.
  1. Phase 1 — VLM이 분석 에세이 대신 핵심 개념 키워드 3~7개만 추출(환각·응답 지연 방지 목적, 사용자에게는 노출하지 않음).
  2. 키워드를 원 질문에 덧붙인 enriched query로 `IntentClassifierService`가 재분류(`classifyEnrichedForImageRag`).
  3. 재분류 결과가 `HYBRID`면 DB를 권위 소스로 삼아 SQL을 우선 실행하고, 결과가 없거나 거부되면 그때만 RAG로 폴백(RAG를 함께 종합하면 일반론이 DB 결과를 덮어써 환각을 유발하기 때문). 그 외 의도는 일반 라우팅과 동일하게 처리.

### 위젯 서비스(`app-widget`)와의 차이

`app-widget`도 동일한 이름의 `QueryRouterService`/`IntentClassifierService`를 갖지만 **웹검색·URL·파일·이미지 경로가 없는 축소판**이다.

| 항목 | 챗 서비스 | 위젯 서비스 |
|------|-----------|-------------|
| 분류 가능 의도 | 9종(RAG/SQL/HYBRID/URL_FETCH/FILE/IMAGE/IMAGE_RAG/WEB_SEARCH/REJECT) | 4종(RAG/SQL/HYBRID/REJECT) |
| 슬래시 커맨드(`/rag` 등) | 있음 | 없음 |
| 웹검색 | SearXNG 연동 | 없음 |
| HYBRID 처리 | RAG+SQL **병렬 실행** 후 LLM 종합(`HybridQueryService`) | 별도 종합 서비스 없이 SQL을 **먼저** 실행 → 결과(행) 있으면 SQL만 사용, 없으면 RAG로 **순차** 폴백 |
| 의도 분류 캐시 | Redis (24h) | 인메모리 `ConcurrentHashMap` |

> 상세 코드 경로·클래스는 [app-internal/README.md](app-internal/README.md#채팅--질의-파이프라인)와 [app-widget/README.md](app-widget/README.md) 참고.

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
| 언어/런타임 | Java 21, TypeScript 5.6, Node 22(`frontend/internal`) / Node 20(`frontend/widget-admin`) |
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

| 서비스 | 설명 | 포트(호스트→컨테이너) | 접속 URL |
|--------|------|:---:|-----|
| `rag-frontend` | 챗 서비스 프론트엔드(SPA, Nginx 서빙) | 18080→80 | http://localhost:18080 |
| `app-internal` | 챗 서비스 백엔드 API(Spring Boot) | 18090→8080 | http://localhost:18090 |
| `widget-admin` | 위젯 서비스 어드민 콘솔(SPA, Nginx 서빙) | 5173→80 | http://localhost:5173 |
| `widget-demo` | 위젯 임베드 데모 페이지(Nginx) | 8080→80 | http://localhost:8080/demo.html |
| `app-widget` | 위젯 서비스 백엔드 API(Spring Boot) | 8081→8081 | http://localhost:8081 |

두 오버레이가 공유하는 인프라 서비스(`compose.base.yml` 및 각 오버레이):

| 서비스 | 설명 | 포트(로컬) |
|--------|------|:---:|
| `ollama` | LLM/임베딩/비전 추론(챗·위젯 공용) | 11434 |
| `pgvector` | 벡터 DB — `ragdb`(챗) + `widget_db`(위젯) | 5432 |
| `redis` | 챗 서비스 ShedLock 분산 스케줄 락 | 6379 |
| `searxng` | 챗 서비스 웹검색 메타서치 엔진 | 18081→8080 |
| `mysql-sample` | 챗 서비스 샘플 외부 DB(binlog 동기화) | 3306 |
| `mariadb-board` | 챗 서비스 샘플 외부 게시판 DB(binlog 동기화) | 3307 |
| `shop-mariadb` | 위젯 서비스 샘플 고객 datasource(`shop_db`) | 3308 |

> 서비스·볼륨 상세 정의는 [infra/README.md](infra/README.md) 참고.

### IDE / CLI 직접 실행 (Docker 없이 개별 서비스만 띄우기)

백엔드는 `ollama`·`pgvector` 등 인프라만 Docker 로 띄운 뒤 IDE 에서 직접 실행할 수 있습니다:

```bash
cd app-internal && ./gradlew bootRun    # 챗 백엔드   → http://localhost:8080
cd app-widget   && ./gradlew bootRun    # 위젯 백엔드 → http://localhost:8081
```

프론트엔드도 Vite dev 서버로 직접 실행할 수 있습니다(핫리로드):

```bash
cd frontend/internal      && npm install && npm run dev  # 챗 프론트   → http://localhost:5173 (프록시: localhost:18090)
cd frontend/widget-admin  && npm install && npm run dev  # 위젯 어드민 → http://localhost:5173 (프록시: localhost:8081)
```

> `frontend/internal` 은 Docker Compose 로 띄운 챗 백엔드(`18090`)를 프록시 대상으로 삼습니다. `./gradlew bootRun`(`8080`)과 함께 쓰려면 `frontend/internal/vite.config.ts` 의 프록시 대상 포트를 맞춰야 합니다. 두 프론트엔드 모두 Vite 기본 포트(`5173`)를 쓰므로 **동시에는 하나만** 실행하세요.
> `frontend/widget-embed` 는 빌드 없는 정적 파일이라 별도 dev 서버가 없습니다 — `demo.html` 을 브라우저로 직접 열거나 Docker Compose 의 `widget-demo`(`:8080`)로 확인하세요.

### 개발 서버 환경 (Jenkins 배포)

개발 서버에서는 **직접 빌드하지 않습니다.** Jenkins 가 이미지를 빌드해 `docker save | ssh docker load` 로 전송하고, `build:` 섹션이 없는 `compose.dev.yml` 로 컨테이너만 교체합니다.

최초 1회 준비:

```bash
# 외부 도커 네트워크 (한 번만)
docker network create rag-net

# infra/.env.dev.example 을 복사해 infra/.env.dev 생성 후 값 채우기
cp infra/.env.dev.example infra/.env.dev
```

```bash
# 개발 서버에서 전체 스택 기동 (Jenkins 가 이미지를 미리 적재한 상태)
docker compose -f infra/compose.dev.yml --env-file infra/.env.dev up -d
```

개발 서버는 로컬 Docker Compose 와 포트가 다릅니다(`ollama` 는 개발 서버 자체 인프라를 사용하며 `compose.dev.yml` 에 포함되지 않음):

| 서비스 | 포트(호스트→컨테이너) | 접속 URL |
|--------|:---:|-----|
| `rag-frontend` | 18080→80 | http://개발서버호스트:18080 |
| `app-internal` | 18090→8080 | http://개발서버호스트:18090 |
| `widget-admin` | 18082→80 | http://개발서버호스트:18082 |
| `widget-demo` | 18083→80 | http://개발서버호스트:18083/demo.html |
| `app-widget` | 18091→8081 | http://개발서버호스트:18091 |
| `pgvector` | 35432→5432 | — |
| `redis` | 6379 | — |
| `searxng` | 8888→8080 | — |

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
| [0006](docs/adr/0006-pdf-ocr-fallback.md) | PDF 텍스트 추출 실패 시 Tesseract OCR 폴백 |
| [0007](docs/adr/0007-multiturn-rag-retrieval.md) | 멀티턴 RAG — 검색 쿼리 재작성 및 WEB_SEARCH 폴백 |
| [0008](docs/adr/0008-pii-masking-all-response-paths.md) | PII 마스킹 원칙 — 모든 LLM 응답 경로에 STANDARD 마스킹 적용 |
| [0009](docs/adr/0009-phase0-admin-web-ui.md) | Phase 0 Admin Web UI — 계정 발급 메일·비밀번호 재설정 |
| [0010](docs/adr/0010-response-raw-storage.md) | LLM 원본 응답 단기 저장소 — PII 마스킹 실패 진단 |
| [0011](docs/adr/0011-self-issued-jwt-auth.md) | 자체 발급 JWT 인증으로 전환 (Open WebUI 세션 제거) |
