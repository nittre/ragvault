# Architecture Decision Records (ADR)

RAG 시스템 프로젝트의 중요한 아키텍처·정책 결정을 기록한다.

## ADR 정책

- **반자동 생성**: 에이전트가 결정 후보를 감지하면 사용자에게 ADR 제안. 사용자 승인 시 생성.
- **단일 출처 원칙**: ADR 은 결정의 권위 출처. `requirements/` 와 충돌하면 ADR 이 우선이며, requirements 갱신은 후속 작업.
- **불변 (Immutable)**: 한 번 Accepted 된 ADR 은 수정하지 않는다. 변경이 필요하면:
  - 새 ADR 작성 → 상태: `Supersedes ADR-NNNN`
  - 기존 ADR 의 상태를 `Superseded by ADR-MMMM` 으로 갱신 (메타데이터만)
- **번호 부여**: `NNNN-kebab-case-slug.md` (4자리 zero-pad)

## 형식

[`0000-template.md`](0000-template.md) 사용. 필수 섹션:
- 메타데이터 (상태·결정일·결정자·관련 ADR·영향 받는 문서)
- 컨텍스트 (Why)
- 결정 (What)
- 결과 (장점·단점·후속 작업)
- 대안 (검토했으나 안 한 옵션)
- 참고

## ADR 목록

| # | 제목 | 상태 | 결정일 |
|---|------|------|--------|
| [0001](0001-binlog-30min-gtid.md) | binlog 동기화 — 30분 주기 + GTID 전용 위치 추적 | Accepted | 2026-05-19 |
| [0002](0002-data-isolation-schema-ready.md) | 직원 간 데이터 격리 — Phase 0 단순 정책 + 스키마는 미리 | Accepted | 2026-05-19 |
| [0003](0003-alb-multi-az-mandatory.md) | ALB Multi-AZ 의무 명시 (컴퓨트만 Single AZ) | Accepted | 2026-05-19 |
| [0004](0004-spring-ai-q4km.md) | LLM 추상화 — Spring AI 전면 채택 + Q4_K_M 양자화 표준 | Accepted | 2026-05-19 |
| [0005](0005-parameter-priority-7-stage.md) | 파라미터 우선순위 — 통합 7단계 체인 + 관리자 가드 분리 | Accepted | 2026-05-19 |
| [0006](0006-user-header-backend-proxy.md) | 사용자 식별 헤더 — Open WebUI 백엔드 프록시 주입 | Accepted | 2026-05-19 |
| [0007](0007-sql-pii-layer-1-3.md) | SQL 결과 PII 마스킹 — Layer 1 + Layer 3 | Accepted | 2026-05-19 |
| [0008](0008-pii-masking-all-llm-paths.md) | PII 마스킹 — 모든 LLM 응답 경로 적용 (ADR-0007 확장) | Accepted | 2026-05-20 |
| [0009](0009-phase0-admin-web-ui.md) | Phase 0 Admin Web UI 도입 (REST API + /admin/* SPA) | Accepted | 2026-05-21 |
| [0010](0010-short-lived-response-storage.md) | PII 마스킹 실패 진단을 위한 원본 응답 Short-lived Storage (Redis 30분 TTL) | Accepted | 2026-05-21 |

## 추가 backlog (ADR 화 검토 후보)

requirements 결정사항 중 아직 ADR 로 추출 안 된 항목 — 필요 시 추가 backfill:

- 비용·일정 단일 출처 ($415 / 3.5~4개월)
- DDL 처리 하이브리드 (LOW/MEDIUM/HIGH)
- 토큰 카운터 — Ollama tokenize + 캐싱
- SES 메일 발송 (상용만)
- 첨부파일 동기 처리 + RabbitMQ 보류
- 멀티모달 듀얼 모델 (qwen2.5:14b + qwen2.5-vl:7b)
- HTTP 타임아웃 600초 통일
- 파일 크기 30MB / 5 files / 200 images
- 분산 트레이싱 Phase 1+ (옵션 1)
- SLA 보류 — 외부 SLA 미정 명문화

위 항목은 후속 ADR 작업으로 backfill 가능. 우선순위는 "결정의 위험도 × 코드 영향 범위"로 판단.

## 참고

- 형식 참고: [Michael Nygard 의 ADR 패턴](https://github.com/joelparkerhenderson/architecture-decision-record)
- 작성·제안 자동화: `.claude/skills/adr-propose/skill.md`
