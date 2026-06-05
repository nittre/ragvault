---
name: team-orchestrator
description: RAG 시스템 프로젝트의 5명 에이전트 팀(planner / backend-engineer / infra-engineer / code-reviewer / verifier)을 구성하여 작업을 분배·조율한다. "팀으로 진행해줘", "팀 만들어줘", "에이전트 팀 시작", 또는 멀티-스텝 코드/인프라 작업 요청이 들어오면 반드시 이 스킬을 호출하라. 단일 에이전트로 해결되는 trivial 작업은 트리거하지 않는다.
---

# Team Orchestrator — RAG 시스템 에이전트 팀 조율

5명 하이브리드 팀(`planner` + `backend-engineer` + `infra-engineer` + `code-reviewer` + `verifier`)을 구성하여 작업을 분배하고 진행을 모니터링한다.

## 언제 트리거하는가

### Should-trigger
- "팀으로 진행해줘", "에이전트 팀 시작", "팀 만들어줘"
- 멀티-스텝 코드 작업 (예: "Spring Boot 부트스트랩 시작", "binlog 동기화 구현")
- 코드 + 인프라가 동시에 영향 받는 작업
- "Phase 0 체크리스트 N번 항목 구현해줘"

### Should-NOT-trigger
- 단일 파일 1줄 수정
- 단순 질문·정보 조회
- 문서만 갱신 (이건 직접 처리)
- ADR 작성만 (→ `adr-propose` 스킬)
- spec 검증만 (→ `spec-check` 스킬)

## 팀 구조

```
planner (opus, 계획·분해·위임)
   │
   ├─→ backend-engineer (sonnet, Spring Boot·Spring AI·pgvector·동기화·SQL·파일·VLM)
   │
   └─→ infra-engineer (sonnet, Docker Compose·Jenkins·배포 자동화)

작업 완료 후:
backend-engineer / infra-engineer
   ↓
code-reviewer (sonnet, 보안·ADR 정합·베스트 프랙티스)
   ↓
verifier (sonnet, 빌드·테스트·경계면·E2E·회귀)
```

### 모델 정책
- **planner**: opus — 작업 분해·우선순위·의존성 판단에 가장 깊은 추론 필요
- **backend / infra / code-reviewer / verifier**: sonnet — 코드 작성·리뷰·검증은 sonnet 충분, 비용 1/5
- 서브 에이전트는 작업 성격에 맞게 **자율 선택** (아래 "서브 에이전트" 섹션 참고)

## 태스크 분배 — 어떻게 일을 나누는가

### 분배 축 4가지

```
1. 도메인 축
   backend-engineer   ← Spring Boot 코드, RAG 로직, Spring AI
   infra-engineer     ← Docker Compose, Jenkins, 배포 자동화

2. 작업 유형 축
   구현 (write)       ← backend / infra
   리뷰 (review)      ← code-reviewer
   검증 (verify)      ← verifier
   분해·위임 (plan)   ← planner

3. 의존성 축 (blocks / blockedBy)
   인프라 먼저 → 코드 (예: DB 스키마 → JPA Entity)
   구현 → 리뷰 → 검증 (단방향, 역행은 재작업 트리거)

4. 마일스톤 축
   Phase 0 작업을 4~6개 마일스톤으로 묶음 (한 마일스톤 = 3~5일)
   예: M1 인프라·로컬 → M2 코어 RAG → M3 binlog 동기화 → M4 SQL·혼합 → M5 파일·URL·VLM
```

### 분배 알고리즘 (planner 가 실행)

```
for 사용자 요청:
    1. 마일스톤 확인 (현재 어디?)
    2. 작업 분해 — 1~3시간 단위
    3. 각 작업에 owner 지정:
       - 도메인 키워드(Spring Boot·RDS·docker compose 등) → backend / infra
       - 작업 유형(rev·verify·plan) → 해당 owner
    4. 의존성 그래프 작성 (blocks/blockedBy)
    5. 병렬 가능 작업 식별 (independent path)
    6. TaskCreate 일괄 등록
    7. 각 task 의 description 에 다음 인용:
       - 관련 ADR-NNNN
       - 관련 requirements/XX 섹션 N
       - 관련 LL-NNNN (lessons-learned, 있으면)
```

### 분배 예시 — "Spring Boot 부트스트랩"

```
[입력] "Spring Boot 부트스트랩 시작해줘. /v1/chat/completions 으로 'hello' 응답 받는 단계까지."

[planner 산출 — 작업 8건]

T1. (infra-engineer) docker-compose.dev.yml 작성
     - Ollama / pgvector / Redis / MySQL 샘플 / Open WebUI
     - blocks: T2, T3, T4
     - 참조: requirements/01 섹션 4-1, requirements/14-4 (로컬 셋업)
     - 예상: 1.5h

T2. (backend-engineer) Spring Boot 프로젝트 부트스트랩
     - build.gradle (Java 21, Spring Boot 3.x, Spring AI 1.0.x)
     - blockedBy: T1
     - 참조: ADR-0004
     - 예상: 1h

T3. (backend-engineer) application-{local,dev,prod}.yml 환경별 설정
     - spring.ai.ollama.base-url (환경별)
     - chat.options.model (qwen2.5:7b 또는 14b q4_K_M)
     - blockedBy: T2
     - 참조: ADR-0004, requirements/02 섹션 "Spring AI"
     - 예상: 1h

T4. (backend-engineer) ChatController + Spring AI ChatClient bean
     - POST /v1/chat/completions (OpenAI 호환 SSE)
     - blockedBy: T2, T3
     - 참조: requirements/01 섹션 3-4, ADR-0004
     - 예상: 2h

T5. (backend-engineer) API Key 인증 필터 + TrustedHeaderFilter
     - blockedBy: T2
     - 참조: requirements/07 섹션 4·8
     - 예상: 2h

T6. (code-reviewer) T1~T5 코드 리뷰
     - 보안·ADR 정합·Spring AI 사용 확인
     - blockedBy: T1, T2, T3, T4, T5
     - 참조: ADR-0001, 0004 / requirements/07
     - 예상: 1h

T7. (verifier) build + test + E2E 검증
     - ./gradlew build, ./gradlew test
     - "hello" → 정상 응답 시나리오
     - blockedBy: T6 (APPROVE 후)
     - 예상: 0.5h

T8. (verifier) spec-check 회귀 검증
     - raw OllamaClient 없는지, access_groups 패턴 적용 준비 등
     - blockedBy: T7
     - 예상: 0.5h

[병렬 가능 경로]
T1 → T2 → [T3, T4, T5 병렬] → T6 → T7 → T8
시리얼: 1.5 + 1 + 2 + 1 + 0.5 + 0.5 = 6.5h
병렬: 1.5 + 1 + max(1,2,2) + 1 + 0.5 + 0.5 = 6.5h (T3·T5 가 T4 의 의존이라 큰 절감 없음)

[lessons-learned 사전 참조]
grep "spring.ai\|application.yml\|ChatClient" docs/lessons-learned/
→ 발견된 LL 의 "비슷한 작업에 적용할 규칙" 을 T2·T3·T4 description 에 인용
```

### 분배 후 TaskCreate 실행

```python
# 의사코드
for task in planner.output:
    task_id = TaskCreate(
        subject=task.subject,
        description=f"""
{task.description}

참조 ADR: {task.adrs}
참조 requirements: {task.reqs}
참조 LL: {task.lessons_learned}
예상 시간: {task.estimate}
        """,
        activeForm=task.active_form
    )
    if task.blocked_by:
        TaskUpdate(task_id, owner=task.owner, blockedBy=task.blocked_by)
    else:
        TaskUpdate(task_id, owner=task.owner)
```

## 서브 에이전트 — 필요 시 자율 spawn

도메인 전문가(backend / infra)가 작업 중 **추가 전문성이 필요한 하위 작업** 을 만나면 자율적으로 서브 에이전트를 spawn 한다.

### 언제 spawn 하는가
- 작업이 본인 도메인 외 영역에 명확히 걸침 (예: backend 가 복잡한 SQL 분석 필요)
- 큰 코드베이스 탐색이 필요한데 본인 컨텍스트가 가득 참
- 병렬로 가능한 독립 하위 작업이 명확

### 서브 에이전트 모델 선택 (자율)

```
모델 선택 가이드:
- 코드 작성·복잡 분석          → sonnet (도메인 전문가와 같은 등급)
- 큰 코드베이스 탐색·grep 다수 → Explore 타입 또는 haiku (가벼움)
- 보안 리뷰·아키텍처 결정 영향 → opus (가장 깊은 추론)
- 단순 정보 조회               → haiku

기본값: sonnet (의심스러우면)
```

### Spawn 방법

도메인 전문가가 `Agent` 도구로 직접 호출:
```
Agent(
    subagent_type: "general-purpose" (또는 Explore 등),
    model: "sonnet" (또는 작업 성격에 맞게),
    prompt: "..."
)
```

### 산출물 통합
서브 에이전트 결과는 원 도메인 전문가가 수신·정리·통합. 직접 main thread 로 반환하지 않음.

## Lessons Learned 통합

### 작업 시작 시 — 참조 (의무)
모든 task 시작 직전, 해당 owner 가 `lessons-learned` 스킬 트리거 → 도메인 키워드로 grep → 발견된 LL 의 규칙 적용.

### 에러 발생 시 — 작성 (의무)
- 빌드·테스트 실패, 런타임 에러, `docker compose up` 실패, 가드레일 차단 + 거부 시 즉시
- `lessons-learned` 스킬 자동 트리거 → 새 LL-NNNN 작성
- spec-check 에 재발 방지 검증 패턴 추가

### 통합 흐름
```
[task 시작]
   ↓ 관련 LL grep
[작업 진행]
   ↓ 에러 발생 시
[LL 작성]
   ↓
[task 재개 또는 재분배]
```

## 워크플로우

### Phase 1: 팀 구성

`TeamCreate` 도구로 5명 팀 생성. 모든 에이전트 `model: "opus"`.

```
TeamCreate(
  team_name: "rag-phase0-team",
  members: [
    { name: "planner",          subagent_type: "general-purpose", model: "opus" },
    { name: "backend-engineer", subagent_type: "general-purpose", model: "opus" },
    { name: "infra-engineer",   subagent_type: "general-purpose", model: "opus" },
    { name: "code-reviewer",    subagent_type: "general-purpose", model: "opus" },
    { name: "verifier",         subagent_type: "general-purpose", model: "opus" }
  ]
)
```

> 빌트인 `general-purpose` 타입을 쓰되, 각 에이전트는 `.claude/agents/{name}.md` 정의를 따른다.

### Phase 2: 작업 분해 — planner 위임

오케스트레이터가 사용자 요청을 받으면 먼저 planner 에게 분해 요청:

```
SendMessage(to: planner, message: """
사용자 요청: {원문}

다음을 수행:
1. 작업 단위(1~3시간 분량)로 분해
2. 각 작업을 backend-engineer / infra-engineer / code-reviewer / verifier 중 적절한 owner 에 할당
3. blocks/blockedBy 의존성 명시
4. 관련 ADR·requirements 섹션 인용
5. ADR 후보 식별 시 adr-propose 스킬 트리거 신호

산출물: TaskCreate 항목 목록 (오케스트레이터가 일괄 생성)
""")
```

### Phase 3: 작업 생성 + 할당

planner 산출물을 받아 `TaskCreate` 로 작업 등록 + `TaskUpdate` 로 owner 지정:

```
for task in planner.output.tasks:
    TaskCreate(subject, description, activeForm)
    TaskUpdate(id, owner: task.assigned_to, blockedBy: task.deps)
```

### Phase 4: 실행 — 도메인 전문가 작업

각 owner 가 자신 task 를 가져가 작업. 협업 패턴:

```
[backend-engineer ↔ infra-engineer]
backend 가 새 환경변수 필요 → SendMessage(to: infra-engineer, "RDS 에 X 컬럼 추가 + compose .env 갱신")

[작업 완료]
backend-engineer 가 작업 완료 → SendMessage(to: code-reviewer, "리뷰 요청: {git diff}, 영향 영역, 관련 ADR")
```

### Phase 5: 리뷰 — code-reviewer

```
code-reviewer 가 BLOCKER/CRITICAL/MAJOR/MINOR severity 로 리뷰:
- APPROVE → verifier 로 진행 신호
- REQUEST CHANGES → 원작성자 (backend/infra) 로 수정 요청 회신
```

### Phase 6: 검증 — verifier

```
verifier 가 빌드·테스트·회귀 검증:
- ACCEPT → 오케스트레이터에 완료 보고, 다음 마일스톤 신호
- REJECT → backend/infra/reviewer 중 적절한 단계로 회신
```

### Phase 7: 종합 보고

오케스트레이터가 결과 수집:

```
- 완료된 작업 목록
- 발생한 새 ADR 후보 (adr-propose 트리거 권장)
- 미완료/실패 작업 + 원인
- 사용자에게 다음 단계 제안
```

## 데이터 전달 프로토콜

| 방식 | 용도 |
|------|------|
| **TaskCreate / TaskUpdate** | 작업 자체 — 진행 상황·의존성·owner |
| **SendMessage** | 팀원 간 실시간 조율·피드백 (BLOCKER 발생, 추가 의존 발견 등) |
| **파일 기반** | 큰 산출물 — `git diff`, docker compose config, 검증 report. `_workspace/` 폴더 또는 PR description |

`_workspace/` 사용 시 파일명 컨벤션: `{phase}_{agent}_{artifact}.{ext}`
- 예: `01_planner_breakdown.md`, `03_backend_chat-controller.diff`, `05_reviewer_feedback.md`

## 에러 핸들링

| 에러 | 처리 |
|------|------|
| 팀원 1회 재시도 후 재실패 | 해당 task 없이 진행, 보고서에 누락 명시 |
| backend ↔ infra 동시 책임 영역 충돌 | 1회 SendMessage 교환, 미해결 시 planner 가 재분배 |
| ADR-요구사항 모순 발견 | 즉시 사용자 escalate. 임의 해석 금지 |
| 가드레일 차단 (위험 명령) | 사용자 명시 승인 필요 — 오케스트레이터가 사용자에게 신호 |

## 팀 크기 가이드 (Phase 0 기준)

```
소규모 작업 (5~10 task): 5명 중 2~3명만 활성
중규모 (10~20):           5명 모두 활성
대규모 (20+):              5명 모두 활성 + 작업 mile stone 으로 분할
```

5명을 매번 모두 깨우지 마라. 작업 성격에 따라 일부만 활성화.
- 인프라 변경 없는 코드 작업 → infra-engineer 휴면
- 코드 변경 없는 인프라 작업 → backend-engineer 휴면

## 테스트 시나리오

### 정상 흐름
1. 사용자: "Spring Boot 부트스트랩 시작해줘"
2. planner: 작업 8개 분해 → TaskCreate 8건
3. backend-engineer: 첫 3개 작업 (build.gradle, application.yml, ChatController)
4. code-reviewer: BLOCKER 0, MAJOR 1 (Spring AI 미통합)
5. backend-engineer: 수정 후 재요청
6. code-reviewer: APPROVE
7. verifier: build PASS, test PASS, ADR 정합 OK
8. 오케스트레이터: 완료 보고 + 다음 마일스톤(binlog 동기화) 제안

### 에러 흐름
1. 사용자: "Phase 0 의 모든 체크리스트 한 번에 구현"
2. planner: 작업 50건 분해
3. 오케스트레이터: 50건이 너무 큼 → planner 에 "마일스톤 3개로 분할" 재요청
4. planner: 마일스톤 1(인프라), 2(코어 RAG), 3(파일·URL·VLM) 으로 재분해
5. 사용자에게 마일스톤별 진행 옵션 제시

## 참고

- 에이전트 정의: `.claude/agents/{planner,backend-engineer,infra-engineer,code-reviewer,verifier}.md`
- 관련 스킬: `adr-propose` (결정 감지 시), `spec-check` (회귀 검증)
- 권위 출처: `docs/adr/README.md`, `requirements/`
- 가드레일: `.claude/hooks/guardrail.py` (위험 명령어 차단)
