# ADR-0009: Phase 0 Admin Web UI 도입 (REST API + /admin/* SPA)

- **상태**: Accepted
- **결정일**: 2026-05-21
- **결정자**: 시니어 UX 리서처 발견 → 사용자 결정
- **관련 ADR**: ADR-0002 (데이터 격리 — admin UI 에서 data_sensitivity 가드)
- **영향 받는 문서**: `requirements/01-architecture.md` 섹션 11·14, `requirements/03-data-sync-pipeline.md`, `requirements/04-rag-search-strategy.md`, `requirements/07-auth-security.md`, `requirements/08-text-to-sql.md`, `requirements/09-user-parameter-tuning.md`

## 컨텍스트

`requirements/01-architecture.md` 섹션 14-9 (Phase 1 정식 출시) 에 "관리자 Web UI (Spring Boot Thymeleaf 또는 Open WebUI 확장)" 가 **Phase 1+** 로 명시되어 있었다.

암묵적 가정 — Phase 0 admin task 는 다음으로 운영:
- Spring Boot REST API (이미 명시: `/api/v1/admin/*`)
- CLI / Postman / curl
- audit log·DDL·사용자 관리 등 모두 API 호출로 처리

### 시니어 UX 리서처가 Customer Admin Journey 점검 중 발견 (2026-05-21)

`docs/ux/admin-journeys.md` 점검 결과:

**A1 (첫 admin 인계) 의 critical 발견** — admin UI 가 없으면 다음 task 모두 부담 큼:
- A2 사용자 추가 (월 5~10건 + 분기 입사 대량 5~20명)
- A3·A4 RAG·SQL 테이블 등록 (필드 11개 + MySQL 스키마 자동 조회 + content_columns 멀티 셀렉트 + 동기화 진행 모니터링)
- A8 DDL 이벤트 처리 (가장 복잡 — 자동 영향 분석 + 위험도별 wizard + Discord deep link)
- A10 Audit Log 조회 (필터 7종 + 사용자별 history)

### Open WebUI 의 한계
Open WebUI Admin Panel 은 **사용자·대화 관리만** 지원. 우리 RAG 시스템 특유 admin task (RAG 테이블·DDL·search_config 등) 는 Open WebUI 에 존재하지 않음. **별도 Web UI 가 불가피**.

### CLI/Postman 한계 (Phase 0 admin 가설)
- 고객사 admin 이 IT 관리자라도 GUI 가 첫인상·신뢰감 결정적
- DDL 처리 — SQL 텍스트 분석·영향 미리보기·위험도 판단 → CLI 로는 admin Power User 도 부담
- 사용자 batch 추가·검색·필터 → CLI 가능하나 매번 admin 이 스크립트 직접 작성 부담
- 영업 단계: "Phase 0 베타 — CLI 만 운영" → 첫 고객사 영업 어려움

## 결정

**Phase 0 부터 admin Web UI 를 도입한다**. Spring Boot REST API + 별도 경로 `/admin/*` SPA (Thymeleaf 또는 React).

### 구체 사양

```
경로:    customera.ragservice.com/admin/*
인증:    Open WebUI 세션 + admin 권한 검증 (X-User-Role: admin)
구현:    Spring Boot 라우팅 또는 ALB target group 분기

Phase 0 화면 7개:
  /admin/users               — A2 사용자 관리 (검색·필터·정렬·CSV upload)
  /admin/rag-tables          — A3 RAG 테이블 (MySQL 자동 조회·체크박스·순서 드래그)
  /admin/sql-tables          — A4 SQL 테이블 (similar)
  /admin/search-config       — A5 검색 파라미터 (search_config 편집)
  /admin/ddl-events          — A8 DDL 이벤트 (자동 영향 분석·위험도별 wizard)
  /admin/audit-logs          — A10 감사 로그 (필터·CSV export)
  /admin/usage-stats         — A7 사용량 통계 (별도 대시보드)
  /admin/api-keys            — A9 API Key 발급·회전
  /admin/param-limits        — A6 admin_param_limits 한도 (Guard A/B)

기술 스택:
  - Frontend: React (또는 Thymeleaf — 간단한 폼만이면)
  - Backend: 이미 명시된 /api/v1/admin/* REST API 그대로 활용
  - 인증: 별도 SPA 라 Open WebUI 백엔드 프록시 경유 X → **Spring Boot 가 Open WebUI 세션 검증 API 호출** (N2 결정)
```

### Admin UI 인증 흐름 (N2 결정)

```
[흐름]
사용자 → admin Web UI (/admin/*)
   │ 1. 같은 도메인 → Open WebUI 세션 쿠키 자동 전송
   ▼
admin UI SPA
   │ 2. API 호출 (예: GET /api/v1/admin/users)
   │    쿠키 포함 (credentials: 'include')
   ▼
Spring Boot
   │ 3. AdminSessionFilter 가 세션 쿠키 추출
   │ 4. Open WebUI /auth/verify 호출 (k3s 내부 통신)
   │    - 세션 유효 여부 확인
   │    - user_email + role 응답
   │ 5. role != 'admin' → 403 Forbidden
   │ 6. role == 'admin' → 통과 + X-User-* 헤더 자동 세팅 (Spring Boot 가 직접)
   ▼
admin API 처리 (UserContext 활용)
```

**Open WebUI 백엔드 측 작업**:
- `/auth/verify` 엔드포인트 신규 추가 (Open WebUI Fork — `docs/policies/openwebui-fork.md` 참고)
- 쿠키 → user 정보 반환

**Spring Boot 측 작업**:
- `AdminSessionFilter` 신규 (X-User-* 직접 세팅, ADR-0006 의 TrustedHeaderFilter 와 다름)
- /admin/* 경로만 적용 (일반 /v1/chat/completions 는 기존 흐름 유지)

**대안 — 사용자 두 곳 로그인 (옵션 B)**: 사용자 부담. 거부.
**대안 — iframe (옵션 C)**: UX 분절·구현 복잡. 거부.

### Phase 1+ 격상 (별도 ADR 또는 본 ADR 후속)
- 인앱 튜토리얼·onboarding wizard
- 클라이언트 실시간 검증
- SSE 진행 모니터링 (현재 폴링)
- Grafana 통합 대시보드 (사용량 통계 격상)
- 부서별 그룹 관리 UI (Phase 1+ access_groups 활성화와 함께)

## 결과

### 장점
- **첫 고객사 영업 가능** — admin GUI 제공이 신뢰·온보딩 결정적
- DDL 처리 등 복잡 task 운영 가능 (Discord deep link → 자동 영향 분석 → 위험도 wizard → 결정)
- 고객사 admin Power User 라도 GUI 가 효율 ↑
- audit·신고 대응 등 사고 시 빠른 추적
- Phase 1+ 격상이 자연스러움 (이미 SPA 라우트 구조 존재)

### 단점·트레이드오프
- **Phase 0 일정 +2~3주** — 약 3.5~4개월 → 약 **4.5~4.7개월**
- Frontend 작업 추가 (React 또는 Thymeleaf 학습·통합)
- 7개 화면 디자인·구현
- backend-engineer 영역 확장 (또는 별도 frontend 작업자 필요)

### 후속 작업
- `requirements/01-architecture.md` 섹션 11 Phase 0 체크리스트에 추가:
  ```
  ☑ admin Web UI (/admin/*) — 7개 화면, React 또는 Thymeleaf
  ```
- `requirements/01-architecture.md` 섹션 14-9 Phase 1+ "관리자 Web UI" → **"관리자 Web UI 강화 (인앱 튜토리얼·실시간 검증·SSE 진행)"** 로 수정 (Phase 0 기본은 이미 도입)
- AMI 빌드 또는 Helm 차트에 admin UI 정적 자산 포함
- `docs/policies/team-and-workflow.md` 의 마일스톤 M1~M5 에 admin UI 마일스톤 추가 (M6 또는 M3 안 통합)
- `docs/ux/admin-journeys.md` 의 결정사항 표가 본 ADR 의 권위 출처

## 대안

### 옵션 B — REST API + CLI 만 (Phase 0 admin UI 없음)
**가장 단순·일정 영향 없음.** 그러나:
- 고객사 admin 이 CLI 운영 → 영업 어려움 (특히 첫 고객사)
- DDL 처리·batch 추가 등 복잡 task 운영 부담 큼
- Phase 1+ Web UI 도입 시 결국 모든 화면 새로 만들어야 함 (지연된 비용)

**거부 이유**: 첫 고객사 매우 critical. 영업 단계의 차별화 부족이 일정 단축 가치보다 큼.

### 옵션 C — Open WebUI Fork 안 admin extension
사용자에게 통합 경험. 그러나:
- Open WebUI Fork 깊이 ↑ ↑ (ADR-0006 백엔드 프록시 fork 외에 frontend fork 도)
- upstream 동기화 부담 매우 큼
- Open WebUI 의 admin 영역 구조가 우리 task 와 다름 (사용자·대화만)

**거부 이유**: Fork 부담 vs 통합 가치 trade-off 에서 별도 경로 (옵션 A) 가 우월.

### 옵션 D — Phase 0 CLI, Phase 1+ Web UI
영업 단계: "Phase 0 베타 — CLI 운영. Phase 1+ 정식 출시 시 Web UI 제공". 일정 단축.

**거부 이유**: 첫 고객사 베타 단계에 admin UX 가 매우 critical. 베타 = "기능 부족 OK"이지만 admin task 운영 자체가 어려워서 admin 이탈 위험.

## 참고

- 권위 출처 발견: `docs/ux/admin-journeys.md` 점검 결과
- 영향: `requirements/01-architecture.md` 섹션 11 (Phase 0 체크리스트) + 14-9 (Phase 1 항목)
- 관련 ADR: ADR-0006 (백엔드 프록시 — admin UI 진입 시 X-User-Role=admin 검증)
- Phase 0 일정 영향: ADR-0001 / 0002 / ... 누적 + 본 ADR = 약 4.5~4.7개월
