# 팀·워크플로우 정책

> 에이전트 5명 + 서브 에이전트 + 스킬 + 태스크 분배 + 새 결정 시 절차.

## 에이전트 팀 (하이브리드 5명)

| 에이전트 | 모델 | 역할 |
|---------|------|------|
| `planner` | **opus** | 작업 분해·위임·우선순위 (코드 안 씀) |
| `backend-engineer` | **sonnet** | Spring Boot·Spring AI·pgvector·동기화·SQL·파일·VLM 코드 |
| `infra-engineer` | **sonnet** | Terraform·k3s·Helm·AWS·SES·AMI |
| `code-reviewer` | **sonnet** | 보안·ADR 정합·베스트 프랙티스 리뷰 (코드 수정 X) |
| `verifier` | **sonnet** | 빌드·테스트·경계면·E2E·회귀 검증 |

정의 위치: `.claude/agents/{name}.md`

### 통신 흐름
```
planner → backend-engineer / infra-engineer (작업 위임)
backend ↔ infra (의존 사항 SendMessage)
backend / infra → code-reviewer (작업 완료 시 리뷰 요청)
code-reviewer → verifier (APPROVE 시)
verifier → planner (완료 또는 재작업)
```

## 서브 에이전트 정책

각 에이전트는 필요 시 **자율적으로** `Agent` 도구로 서브 에이전트 spawn 가능.

### 모델 자율 선택 가이드
| 작업 성격 | 모델 |
|----------|------|
| 코드 작성·복잡 분석 | sonnet (기본값) |
| 큰 코드베이스 탐색·grep 다수 | Explore 또는 haiku |
| 보안 리뷰·아키텍처 영향 큰 결정 | opus |
| 단순 정보 조회 | haiku |

산출물은 spawn 한 에이전트가 수신·정리·통합. **main thread 로 직접 반환 X**.

## 스킬 (자동 트리거)

| 스킬 | 트리거 |
|------|--------|
| `team-orchestrator` | 멀티-스텝 코드/인프라 작업, "팀으로 진행해줘" |
| `adr-propose` | 옵션 결정·모순 해결·새 기술 채택 감지 (반자동, 사용자 승인 필요) |
| `spec-check` | "결정사항 위반 없는지", 회귀 검증 |
| `lessons-learned` | 에러 발생 시 LL 작성 + 작업 시작 시 관련 LL 참조 (의무) |

위치: `.claude/skills/{name}/skill.md`

## 태스크 분배

### 4축
```
1. 도메인 축       backend(Spring Boot) ↔ infra(AWS·k3s)
2. 작업 유형 축    plan / write / review / verify
3. 의존성 축       blocks · blockedBy
4. 마일스톤 축     Phase 0 = M1~M5 (인프라 → 코어 RAG → 동기화 → SQL/혼합 → 파일/URL/VLM)
```

### planner 의 알고리즘
```
입력: 사용자 요청 (예: "Spring Boot 부트스트랩 시작")

1. 마일스톤 위치 확인
2. 작업 분해 — 각 1~3시간 단위
3. owner 지정
   - Spring Boot·Spring AI·pgvector·SQL → backend-engineer
   - Terraform·Helm·AWS·SES·AMI         → infra-engineer
   - 구현 완료 후                         → code-reviewer
   - 리뷰 통과 후                         → verifier
4. 의존성 그래프 (blocks / blockedBy)
5. 병렬 가능 경로 식별
6. lessons-learned grep — 관련 LL 규칙 작업 description 에 사전 인용
7. TaskCreate 일괄 등록 + TaskUpdate(owner, blockedBy)
```

### 데이터 전달 프로토콜
| 방식 | 용도 |
|------|------|
| TaskCreate / TaskUpdate | 작업 자체 — 진행 상황·의존성·owner |
| SendMessage | 팀원 간 실시간 조율·피드백 |
| 파일 기반 | 큰 산출물 (git diff, terraform plan, Verification Report) |

`_workspace/` 사용 시 파일명: `{phase}_{agent}_{artifact}.{ext}`
예: `01_planner_breakdown.md`, `03_backend_chat-controller.diff`, `05_reviewer_feedback.md`

### 분배 예시 — "Spring Boot 부트스트랩"
```
T1 (infra)    docker-compose.dev.yml                  → 1.5h, blocks T2~T5
T2 (backend)  build.gradle (Spring Boot + Spring AI)   → 1h,   blockedBy T1
T3 (backend)  application-{local,dev,prod}.yml        → 1h,   blockedBy T2
T4 (backend)  ChatController + ChatClient bean        → 2h,   blockedBy T2,T3
T5 (backend)  API Key Filter + TrustedHeaderFilter    → 2h,   blockedBy T2
T6 (reviewer) T1~T5 리뷰 (ADR-0004 정합 등)            → 1h,   blockedBy T1~T5
T7 (verifier) build + test + "hello" E2E              → 0.5h, blockedBy T6
T8 (verifier) spec-check 회귀 (raw OllamaClient 등)    → 0.5h, blockedBy T7

병렬 경로: T1 → T2 → [T3,T4,T5 동시] → T6 → T7 → T8
```

## 새 결정 시 절차

1. 작업 시작 전 — `docs/adr/README.md` 와 `requirements/` 권위 출처 확인
2. 옵션 검토 — 시니어 의견 + 트레이드오프 정리
3. 사용자 결정 — 옵션 선택
4. **즉시 `adr-propose` 스킬 호출** — ADR 작성
5. ADR 작성 후 — 영향 받는 `requirements/` 문서 갱신
6. 코드 변경 — backend / infra 팀이 ADR 권위 출처로 인용하며 작업
7. `code-reviewer` 가 ADR 정합 검증
8. `verifier` 가 회귀 검증 (`spec-check` 스킬 활용)

## 팀 크기 가이드

```
소규모 (5~10 task):  5명 중 2~3명만 활성
중규모 (10~20):       5명 모두 활성
대규모 (20+):         5명 모두 + 작업 마일스톤 분할
```

매번 5명 모두 깨우지 마라. 인프라 변경 없는 코드 작업이면 infra-engineer 휴면, 반대로도 마찬가지.

## 참고

- 에이전트 정의: `.claude/agents/{name}.md`
- 오케스트레이터 스킬: `.claude/skills/team-orchestrator/skill.md`
- 관련 정책: [decisions-and-lessons.md](decisions-and-lessons.md), [security-and-guardrails.md](security-and-guardrails.md), [engineering-conventions.md](engineering-conventions.md)
