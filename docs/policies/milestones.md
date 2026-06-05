# Phase 0 마일스톤

> Phase 0 (RAG 사내 서비스 MVP) 작업을 8개 마일스톤으로 분할.
> 각 마일스톤 끝에 **데모 가능 상태** + 명확한 Exit criteria.
> 4.5~4.7개월의 Phase 0 가 두루뭉술하게 흘러가지 않도록 통제 단위.

## 마일스톤 정의 원칙

```
- 1~4주 단위 작업 묶음 (4주 초과면 분할)
- 각 M 끝에 영업·고객사 시연 가능 상태
- 명확한 Entry / Exit criteria
- 의존성 명시 (DAG)
- 인력 분담·병렬화 가능
```

---

## M0 — 사전 준비

**Entry**: requirements·ADR·정책 문서 동결, 사용자 결정 완료

**작업 영역**:
- `rag-backend` 리포 부트스트랩 (Java 21 + Spring Boot 3.x + Spring AI 1.0.x)
- `rag-infra` 리포 부트스트랩 (인프라 관리 도구 디렉토리 구조)
- `docker-compose.dev.yml` — Ollama / pgvector / Redis / MySQL 샘플 / Open WebUI
- 로컬 Ollama: `qwen2.5:7b-instruct-q4_K_M`, `bge-m3` pull
- Jenkinsfile 기본 (build + test)
- `.editorconfig` / 코드 스타일 / Gradle 설정

**Exit criteria**:
- ☐ `docker compose -f docker-compose.dev.yml up -d` → 6개 컨테이너 동작
- ☐ `./gradlew build` → 성공
- ☐ 첫 PR 자동 빌드·테스트 동작 (Jenkins)
- ☐ Open WebUI http://localhost:3000 접속 → 로그인 화면

**의존**: 없음
**데모**: "로컬 환경 셋업 완료, 다음 마일스톤 진입 가능"

---

## M1 — 코어 RAG MVP

**Entry**: M0 완료

**작업 영역**:
- Spring AI `ChatClient` + `OllamaChatModel` 통합
- pgvector 스키마 + Flyway 마이그레이션 (document_chunks · access_groups 컬럼 포함 — ADR-0002)
- `POST /v1/chat/completions` OpenAI 호환 API + SSE 스트리밍
- `POST /v1/embeddings` (필요 시)
- 단순 RAG 흐름: 질문 → 임베딩 (nomic) → pgvector 검색 (cosine) → LLM (qwen2.5:7b)
- ApiKeyAuthFilter (bcrypt + key_prefix 인덱스)
- TrustedHeaderFilter (X-User-* — ADR-0006, 단 M5 전까지는 외부 IP 라 헤더 제거됨)
- 환경별 application-{local,dev,prod}.yml (Spring AI 모델 매핑)

**Exit criteria**:
- ☐ Open WebUI 에 API Key 등록 → "안녕" → "안녕하세요" 응답
- ☐ pgvector 에 청크 수동 INSERT → 그 청크 기반 답변
- ☐ `./gradlew test` 통과 (단위 + 통합)
- ☐ API Key 검증 동작 (bcrypt match, Rate Limit, scope)

**의존**: M0
**데모**: ★ **첫 시연 가능 — "Spring AI 챗봇 동작"** (영업 첫 미팅 자료)

---

## M2 — 데이터 동기화

**Entry**: M1 완료

**작업 영역**:
- `mysql-binlog-connector-java` 통합 + GTID 위치 추적 (ADR-0001)
- `@Scheduled(cron="0 */30 * * * *")` + ShedLock (Redis 분산 락)
- `rag_table_config` 동적 관리 + 등록 가드 (data_sensitivity restricted 거부, ADR-0002)
- `PiiMasker` 정규식 8개 PII (이름·주민·전화·이메일·주소·계좌·카드·사번)
- 청킹 (LangChain4j + 토큰 카운팅 — Ollama tokenize API + Redis 캐시)
- `document_chunks` UPSERT + content_hash 멱등성
- `binlog_events` 실패 추적 + Spring Retry 3회
- `ddl_events` 하이브리드 처리 (LOW 자동 / MEDIUM 7일 / HIGH 수동)
- 초기 동기화 API (`POST /api/v1/admin/sync/initial`)

**Exit criteria**:
- ☐ 회사 MySQL 샘플 INSERT/UPDATE/DELETE → 30분 cron 안 pgvector 반영
- ☐ DDL 발생 → ddl_events 기록 + Discord 알람
- ☐ binlog 1시간 lag 시 Warning 알람
- ☐ 초기 동기화 10만 행 → ~1.75시간 안 완료

**의존**: M1
**데모**: **"회사 MySQL 자동 동기화"** — 실제 데이터 기반 RAG

---

## M3 — Text-to-SQL + Hybrid

**Entry**: M1 완료 (M2 와 병렬 가능)

**작업 영역**:
- 의도 분류기 (qwen2.5 LLM + Redis 캐시 24h) — RAG/SQL/HYBRID
- 정적 규칙 우선 분류 (M4·M5 까지 누적 — 6경로 완성은 M4)
- 스키마 자동 조회 (INFORMATION_SCHEMA + Redis 1시간 캐시)
- Few-shot 프롬프트 (sample_queries 활용)
- `SqlValidator` (JSqlParser AST 파싱 + 컬럼 화이트리스트 + SELECT * 차단 — ADR-0007)
- Read-only MySQL 계정 (`rag_readonly`) + 쿼리 타임아웃 10초 + LIMIT 1000 강제
- 결과 자연어 변환 LLM
- Hybrid 병렬 실행 (`CompletableFuture`) + 종합 LLM
- 부분 결과 progressive 표시 (SSE 이벤트)
- **PII 마스킹 모든 응답** (ADR-0008): RAG/SQL/HYBRID 응답 후처리
- **원본 응답 short-lived storage** (ADR-0010): Redis 30분 TTL
- `sql_table_config` 동적 관리 + `sql_execution_log`

**Exit criteria**:
- ☐ "지난달 매출" → SQL 생성·실행·자연어화 정상
- ☐ "고객 수와 보증 정책" → Hybrid 병렬 실행
- ☐ `DROP TABLE` 시도 → SqlValidator 거부 + 오류 ID
- ☐ PII 마스킹 회귀 검증 (spec-check) 통과
- ☐ Redis 원본 응답 30분 TTL 확인

**의존**: M1
**데모**: **"수치 답변 + 혼합 답변"** — Text-to-SQL 차별화 영업 자료

---

## M4 — URL/파일/멀티모달

**Entry**: M1 완료 (M2/M3 와 병렬 가능)

**작업 영역**:
- 의도 분류 정적 규칙 (URL 패턴·file_ids·images 우선 분기 — 6경로 완성)
- URL Fetch: readability4j + SSRF Guard (private IP / DNS rebinding / Content-Length 5MB / timeout 30초)
- 첨부파일: Apache Tika + Tesseract OCR (kor+eng) + 신뢰도 임계값 70% fallback (N4)
- 파일 처리 동기 (RabbitMQ 미사용, 600초 타임아웃)
- 멀티모달: `qwen2.5-vl:7b-instruct-q4_K_M` 별도 ChatClient bean (듀얼 모델)
- 파일 처리 DB (`file_processing`) + S3 + 24h lifecycle
- `POST /v1/files` 엔드포인트
- file_id 후속 컨텍스트 유지
- 모든 경로 PII 마스킹 + 원본 short-lived

**Exit criteria**:
- ☐ URL 첨부 → 본문 fetch + 답변
- ☐ PDF/PPT 첨부 → Tika + OCR + 답변 (~30~80초)
- ☐ 이미지 첨부 → VLM 답변
- ☐ SSRF 거부 (private IP 시도 차단)
- ☐ 파일 크기 30MB·이미지 200개 한도 동작
- ☐ 6경로 의도 분류 정확도 ≥ 90% (Golden Dataset 50개)

**의존**: M1
**데모**: **"PDF·이미지·URL 분석"** — ChatGPT급 멀티모달

---

## M5 — Open WebUI Fork

**Entry**: M1 완료 (M2/M3/M4 와 병렬 가능 — 프론트 인력 별도)

**작업 영역**:
- Open WebUI 상위 버전 결정 + fork 브랜치 구조 (`docs/policies/openwebui-fork.md` 참고)
- **Backend (Python/FastAPI)**:
  - `/v1/chat/completions` 프록시 (X-User-* 헤더 주입 — ADR-0006)
  - `/v1/files` 프록시 → Spring Boot
  - `/auth/verify` 엔드포인트 신규 (admin UI 세션 검증용 — ADR-0009)

- **Frontend (Svelte)**:
  - 사이드 패널 13 파라미터 (디폴트 접힘 — S1 #11)
  - 클립 버튼 (파일·이미지 통합)
  - 출처 카드 (Top-K 전부, 점수 비노출, [N] 인라인 인용)
  - 좋아요/싫어요 사유 폼 (싫어요 mini-form)
  - "이 답변 다시 받기" 토스트 (모델/파라미터 변경 시)
  - 의도 분류 라벨 (📊/📄/🔀/🌐/📎/🖼)
  - 파일 첨부 미리보기 카드 (다중 + 누적 크기)
  - Fallback 카드 [다시 시도] [상태 페이지] 링크
  - SSE 단절 시 "응답이 중단되었습니다" 라벨
  - 페이지 title + favicon (화이트라벨 최소)

**Exit criteria**:
- ☐ Open WebUI → 우리 백엔드 채팅 정상 동작
- ☐ X-User-* 헤더 위변조 시도 → 백엔드가 무시
- ☐ 사용자가 사이드 패널·클립·좋아요/싫어요 모두 사용 가능
- ☐ 모델·파라미터 변경 시 토스트 동작
- ☐ Fallback 카드 + SSE 단절 표시 정상
- ☐ upstream 동기화 1회 (회귀 테스트 통과)

**의존**: M1
**데모**: ★ **"ChatGPT-like UX 완성"** — 채팅 화면 완전한 사용자 경험

---

## M6 — Admin Web UI

**Entry**: M2, M3 완료 (admin 이 다룰 데이터 모델 안정)

**작업 영역**:
- `/admin/*` SPA 신규 (React + Spring Boot static 또는 Thymeleaf)
- `AdminSessionFilter` (Spring Boot) — Open WebUI `/auth/verify` 호출 + role 확인 (N2)
- 9개 화면:
  - `/admin/users` — CSV upload + 검색·필터·정렬 (A2)
  - `/admin/rag-tables` — MySQL 자동 조회 + 컬럼 체크박스 + 순서 드래그 + 등록 후 동기화 모달 (A3)
  - `/admin/sql-tables` — excluded_columns PII 자동 추천 + sample_queries wizard (A4)
  - `/admin/search-config` — 동적 설정
  - `/admin/ddl-events` — 자동 영향 분석 패널 + 위험도별 wizard + Discord deep link (A8)
  - `/admin/audit-logs` — 사용자 필터 + 기간 + action + **원본 조회** (ADR-0010, 30분 TTL 안만)
  - `/admin/usage-stats` — 일일 질의·피드백·실패 통계
  - `/admin/api-keys` — 발급/회전/폐기
  - `/admin/param-limits` — Guard A 클램핑 / Guard B 강제 고정
- 신규 scope `api:incident-response` (ADR-0010)

**Exit criteria**:
- ☐ admin 로그인 → 9개 화면 모두 동작
- ☐ CSV 5명 batch upload 정상
- ☐ DDL 영향 분석 패널 정확
- ☐ audit_log 원본 조회 (30분 TTL 안) 정상
- ☐ scope 검증 (`api:incident-response` 없으면 거부)

**의존**: M2, M3
**데모**: **"admin 자체 운영 가능"** — 고객사 admin 인계 가능 상태

---

## M7 — 인프라 (병렬 진행)

**Entry**: M0 완료

**작업 영역**:


  - Private App Subnet AZ-a + AZ-c (AZ-c reserve)
  - Private LLM Subnet AZ-a (GPU 노드)
  - Private Data Subnet AZ-a + AZ-c (RDS Multi-AZ)
  - NAT Gateway 1개 (AZ-a)




  - Secrets Manager + KMS

- docker-compose.prod.yml: rag-backend, open-webui, redis, prometheus, grafana

- Jenkins 파이프라인: build → ECR push → docker compose up -d
- Route 53 hosted zone + ACM 자동 검증

**Exit criteria**:
- ☐ 
- ☐ docker compose up -d → 컨테이너 5종 정상 기동
- ☐ Ollama 기동 + 모델 pull 확인
- ☐ Jenkins  배포 성공
- ☐ ALB 외부 접근 → SSE 스트리밍 정상 (600초 timeout)

**의존**: M0
**데모**: "신규 고객사 인프라 자동 배포"

---

## M8 — 운영 준비 + E2E

**Entry**: M5, M6, M7 완료

**작업 영역**:
- Runbook 5개: ollama-llm-down · pgvector-down · customer-mysql-disconnect · disk-full · spot-interruption
- 상태 페이지 `/status` 정적 HTML (Spring Boot `/api/v1/health/deep` 기반 — N1 결정의 일부)
- Prometheus 메트릭 + Grafana 대시보드 (RAG·SQL·HYBRID·FILE·URL·IMAGE 경로별 + binlog lag + 가드레일 발동)
- CloudWatch 알람 + Discord Webhook (Critical / Warning / Info 3채널)
- 보안 체크리스트 ([07-auth-security.md](../../requirements/07-auth-security.md) 끝 부분) 전부 통과
- 부하 테스트 (30명 동시 — Phase 0 가정)
- spec-check 회귀 검증 전 영역 통과
- 고객사 admin 인계 시나리오 리허설 (A1)
- 사용자 가이드 `docs/user-guide.md` + admin 가이드 `docs/customer-admin-guide.md`
- 메일 templating (계정 발급 / 비밀번호 재설정)

**Exit criteria**:
- ☐ 모든 Runbook 작성 + 1회 시뮬레이션 통과
- ☐ Grafana 대시보드 3개 (메인·장애·비즈니스) 동작
- ☐ Critical / Warning / Info Discord 채널 알람 정상
- ☐ 부하 테스트 통과 (30명 동시, p99 응답시간 정상)
- ☐ 보안 체크리스트 100% 통과
- ☐ 첫 고객사 admin 인계 리허설 완료

**의존**: M5, M6, M7
**데모**: ★ **"고객사 첫 베타 출시 가능"**

---

## 의존성 DAG

```
M0
 ├─→ M1 ─┬─→ M2 ─┐
 │       ├─→ M3 ─┼─→ M6 ─┐
 │       ├─→ M4 ─┘       │
 │       └─→ M5 ─────────┼─→ M8 → 출시
 └─→ M7 ──────────────────┘
```

## 각 M 끝의 데모 스토리

| M | 데모 가치 |
|---|----------|
| M1 | "Spring AI 챗봇 동작" — 영업 첫 시연 |
| M2 | "회사 MySQL 자동 동기화" — 실제 데이터 RAG |
| M3 | "수치·혼합 답변" — Text-to-SQL 차별화 |
| M4 | "PDF·이미지·URL 분석" — ChatGPT급 |
| M5 | "ChatGPT-like UX 완성" |
| M6 | "admin 자체 운영" — 고객사 자립 |
| M7 | "신규 고객사 자동 배포" |
| M8 | "베타 출시" |

## 마일스톤 변경 정책

- 새 마일스톤 추가·기존 변경 시 ADR 후보 (큰 변경) 또는 본 문서 직접 갱신 (작은 조정)
- 마일스톤 안의 작업 항목 추가·제거는 자유
- Exit criteria 변경은 신중 (마일스톤의 본질)

## 참고

- 권위 출처: 본 문서 + 각 마일스톤에 인용된 ADR·requirements·user-journeys.md
- 인덱스: [`CLAUDE.md`](../../CLAUDE.md), [`requirements/01-architecture.md`](../../requirements/01-architecture.md) 섹션 11
- 진행 추적: planner 에이전트가 마일스톤별 TaskCreate 분해
