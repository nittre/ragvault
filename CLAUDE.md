# CLAUDE.md — RagVault 프로젝트 진입점

> 짧은 진입점. 상세 정책은 [`docs/policies/`](docs/policies/) 4개 문서를 참조한다.

## 프로젝트 한 줄

**사내 MySQL 데이터를 기반으로 자연어 RAG·SQL·혼합 질의응답을 제공하는 Spring Boot + Open WebUI + Ollama + pgvector 사내 서비스.**

상세: [`requirements/TEAM-OVERVIEW.md`](requirements/TEAM-OVERVIEW.md)

---

## 정책 문서 (필독)

| 정책 | 내용 |
|------|------|
| [팀·워크플로우](docs/policies/team-and-workflow.md) | 역할 분담 + 태스크 분배 + 새 결정 시 절차 |
| [결정·기록](docs/policies/decisions-and-lessons.md) | ADR 정책 (immutable) · LL 정책 (append-only) |
| [보안·가드레일](docs/policies/security-and-guardrails.md) | 위험 명령 차단 + 10가지 보안 원칙 (PII·access_groups·SSRF·인증) |
| [엔지니어링 컨벤션](docs/policies/engineering-conventions.md) | 코드 작성 10원칙 + 환경별 차이 + 자주 쓰는 명령어 + 디렉토리 구조 |
| [Open WebUI Fork](docs/policies/openwebui-fork.md) | Fork 범위 명시(Backend·Frontend 변경 파일 목록) + upstream 동기화 전략 + Fork 깊이 최소화 원칙 |
| [Phase 0 마일스톤](docs/policies/milestones.md) | M0~M8 분해 + 각 Exit criteria + 의존성 DAG + 데모 스토리 |

---

## 권위 출처 (single source of truth)

| 영역 | 출처 |
|------|------|
| 시스템 전반·아키텍처 | [`requirements/01-architecture.md`](requirements/01-architecture.md) |
| Phase 0 일정 (약 3.5~4개월) | [`requirements/01-architecture.md`](requirements/01-architecture.md) 섹션 11 |
| 모든 결정사항 | [`docs/adr/`](docs/adr/) (ADR-NNNN) |
| 기술 스택 | [`requirements/02-stack-reference.md`](requirements/02-stack-reference.md) |
| 데이터 동기화 | [`requirements/03-data-sync-pipeline.md`](requirements/03-data-sync-pipeline.md) |
| RAG 검색 | [`requirements/04-rag-search-strategy.md`](requirements/04-rag-search-strategy.md) |
| 프롬프트 | [`requirements/05-prompt-design.md`](requirements/05-prompt-design.md) |
| 에러·운영 | [`requirements/06-error-handling.md`](requirements/06-error-handling.md) |
| 인증·보안 | [`requirements/07-auth-security.md`](requirements/07-auth-security.md) |
| Text-to-SQL | [`requirements/08-text-to-sql.md`](requirements/08-text-to-sql.md) |
| 사용자 파라미터 | [`requirements/09-user-parameter-tuning.md`](requirements/09-user-parameter-tuning.md) |
| URL·파일·멀티모달 | [`requirements/10-multimodal-files-url.md`](requirements/10-multimodal-files-url.md) |

**원칙**: ADR 과 requirements 가 충돌하면 ADR 이 우선. requirements 갱신은 후속 작업.

---

## 결정된 ADR (인덱스)

| ADR | 결정 |
|-----|------|
| [ADR-0001](docs/adr/0001-binlog-30min-gtid.md) | binlog 30분 + GTID 전용 (옵션 B) |
| [ADR-0002](docs/adr/0002-data-isolation-schema-ready.md) | 데이터 격리 — Phase 0 단순 + 스키마 미리 (옵션 D) |
| [ADR-0003](docs/adr/0003-alb-multi-az-mandatory.md) | ALB Multi-AZ 의무, 컴퓨트 Single AZ |
| [ADR-0004](docs/adr/0004-spring-ai-q4km.md) | Spring AI 전면 + Q4_K_M 양자화 |
| [ADR-0005](docs/adr/0005-parameter-priority-7-stage.md) | 파라미터 7단계 우선순위 + Guard 분리 |
| [ADR-0006](docs/adr/0006-user-header-backend-proxy.md) | ~~사용자 식별 헤더 — Open WebUI 백엔드 프록시 주입~~ **[Superseded by ADR-0011]** |
| [ADR-0007](docs/adr/0007-sql-pii-layer-1-3.md) | SQL 결과 PII 마스킹 — Layer 1 + Layer 3 |
| [ADR-0008](docs/adr/0008-pii-masking-all-llm-paths.md) | PII 마스킹 — 모든 LLM 응답 경로 적용 |
| [ADR-0009](docs/adr/0009-phase0-admin-web-ui.md) | Phase 0 Admin Web UI 도입 (`/admin/*` SPA) — N2 인증 방식 ADR-0011로 개정 |
| [ADR-0010](docs/adr/0010-short-lived-response-storage.md) | PII 마스킹 실패 진단 — 원본 응답 Short-lived Storage (Redis 30분 TTL) |
| [ADR-0011](docs/adr/0011-jwt-auth-replace-openwebui-session.md) | 자체 JWT 인증 — Open WebUI 세션 교체, React SPA 도입 |

추가 backfill 후보 + 전체 정책: [`docs/adr/README.md`](docs/adr/README.md)

---

## 보류된 영역 (Phase 0 미해결, Phase 1+ 검토)

- Jenkins 보안 격리 (권한 최소화, GitHub Actions OIDC 검토)
- PIPA 컴플라이언스 (법무·영업 트랙)
- 외부 SLA 정의 (영업·법무·CTO 결정)
- 분산 트레이싱 (OpenTelemetry + Grafana Tempo)
- Disaster Recovery (멀티 리전)
- 운영팀/온콜 구성

→ Phase 0 출시 차단 영역 아님. 실제 운영 시점에 결정.

---

## 참고

- 정책 문서 4개는 작업 시 참조. CLAUDE.md 는 진입점/인덱스 역할만.
