---
name: lessons-learned
description: 에러·실패·예상치 못한 행동 발생 시 docs/lessons-learned/ 에 LL-NNNN 문서를 작성한다. 또한 새 작업을 시작하기 전 관련 카테고리의 기존 LL 을 grep 으로 검색하여 동일한 실수를 반복하지 않게 한다. 빌드 실패, docker compose up 에러, 런타임 예외, 30분 이상 디버깅, 가드레일 차단 등 모든 실패 신호에 트리거하라. 작업 시작 시점에도 자동으로 관련 LL 을 참조하라.
---

# Lessons Learned 스킬

에러 발생 시 LL-NNNN 작성, 새 작업 시작 시 관련 LL 자동 참조.

## 두 가지 트리거

### 트리거 A — 새 작업 시작 시 (참조 모드)
**언제**: 에이전트가 새 task 시작 직전.

**행동**:
1. 작업 도메인 키워드 추출 (예: "binlog 동기화 구현" → `binlog`, `GTID`, `BinaryLogClient`)
2. `docs/lessons-learned/` 에서 grep
3. 발견된 LL 의 "비슷한 작업에 적용할 규칙" 섹션을 우선 적용
4. 적용한 LL 번호를 task description 또는 작업 로그에 인용

```bash
# 도메인별 빠른 grep
grep -rln "spring.ai\|OllamaChatModel\|ChatClient" docs/lessons-learned/
grep -rln "terraform\|aws_lb\|aws_rds" docs/lessons-learned/
grep -rln "binlog\|GTID\|BinaryLogClient" docs/lessons-learned/
grep -rln "PII\|piiMasker\|access_groups" docs/lessons-learned/
grep -rln "Tika\|Tesseract\|OCR" docs/lessons-learned/
```

### 트리거 B — 에러 발생 시 (기록 모드)
**언제 트리거 (반드시)**:
- 빌드·테스트 실패 (`./gradlew build`, `npm test` 등 명령 실패)
- 런타임 에러 (Spring Boot 시작 실패, NullPointerException, BeanCreationException)
- 인프라 적용 실패 (`docker compose up` 에러, `docker compose up` 실패)
- 가드레일에 의해 위험 명령 차단 (사용자가 차단 후 거부한 경우)
- 통합 경계면 mismatch (API shape 불일치 등)
- 디버깅 시간 30분 초과 영역

**언제 트리거 안 함**:
- 단순 오타·즉시 수정되는 문법 오류
- 이미 동일 패턴의 LL 이 존재 (참조만)
- 작업 중 중간 에러지만 의도된 흐름 (예: test red→green 사이클)

## 워크플로우 — 트리거 B (기록 모드)

### 1. 에러 발생 즉시 단계

에이전트가 에러를 만나면 작업 일시 중단 후 다음 정보를 수집:
- 실행한 명령어 (전체)
- 에러 메시지 + stack trace (있으면 전부)
- 예상한 동작 vs 실제 동작
- 변경된 파일 목록 (`git status` / `git diff`)
- 관련 환경(profile, version 등)

### 2. 중복 LL 검색

```bash
# 에러 메시지의 핵심 키워드로 grep
grep -rln "{핵심키워드}" docs/lessons-learned/

# 카테고리·심각도 별 빠른 인덱스 조회
cat docs/lessons-learned/README.md | grep "{카테고리}"
```

기존 LL 이 있으면:
- 동일 패턴 → LL 본문 따라 해결
- 유사 패턴 → 기존 LL "재발 방지" 가 왜 작동 안 했는지 분석, 새 LL 작성 + 기존 LL "참고" 섹션에 cross-link

### 3. LL 작성

새 번호 결정:
```bash
# 다음 번호 찾기
ls docs/lessons-learned/ | grep -E '^[0-9]{4}-' | tail -1
```

`docs/lessons-learned/NNNN-slug.md` 작성. `0000-template.md` 구조 따름:
- 메타데이터 (발생일·카테고리·심각도·관련 작업·관련 ADR/Requirements)
- 에러 상황 (시간 순)
- 원인 (5 Whys, 표면 증상이 아니라 근본 원인)
- 해결 (임시 + 영구)
- 재발 방지 (시스템·자동화, 사람 약속 X)
- 비슷한 작업에 적용할 규칙 (When you see X, do Y — 가장 중요한 섹션)

### 4. README 인덱스 갱신

`docs/lessons-learned/README.md` 인덱스 표에 항목 추가:
```markdown
| [NNNN](NNNN-slug.md) | 제목 | category | severity | YYYY-MM-DD |
```

### 5. 관련 자료 cross-link

- ADR 과 관련된 경우: ADR 마지막에 "관련 LL: LL-NNNN" 추가
- requirements 회귀가 원인인 경우: requirements 문서 상단에 LL 인용
- spec-check 스킬에 새 회귀 검증 패턴 추가 (재발 방지 자동화)

## 작성 원칙

| 원칙 | 설명 |
|------|------|
| **즉시 작성** | 에러 직후. "나중에"는 잊어버린다. |
| **근본 원인** | "테스트 안 돌려서"가 아니라 "왜 테스트 안 돌리는 문화가 됐는지" |
| **재발 방지는 시스템** | 사람 약속 X. 자동 검증·체크리스트·코드 변경. |
| **단정형 규칙** | "X 를 본다 → Y 한다" 형식의 명확한 trigger-action |
| **시간 순서** | 추측 말고 실제로 일어난 순서 (재현 가능성) |
| **자체 완결성** | 외부 컨텍스트 없이 6개월 후 이해 가능 |

## 트리거 검증

### Should-trigger (이 스킬 발동)
- "테스트 실패", "빌드 실패", "에러 발생"
- `docker compose up` 또는 `docker compose up` 실패
- Spring Boot 시작 시 BeanCreationException 등
- 가드레일 차단 후 사용자가 명시 거부
- "이거 30분째 못 풀고 있어"
- 작업 시작 전 (참조 모드)

### Should-NOT-trigger
- 단순 질문, 정보 조회
- 디자인 토론
- 즉시 수정되는 오타
- 의도된 test fail (TDD red 단계)
- 이미 동일 LL 가 있고 따라가는 중

## 카테고리 분류 가이드

| 키워드 | 카테고리 |
|--------|---------|
| Gradle, Maven, classpath, dependency | `build` |
| Spring Bean, application context, profile, NullPointerException | `runtime` |
| Docker Compose, Jenkins, 배포 | `infra` |
| DB 스키마, 마이그레이션, binlog, GTID, pgvector | `data` |
| PII, 인증, SSRF, access_groups, API Key | `security` |
| API shape, Open WebUI ↔ Spring Boot, JSON schema | `integration` |
| Spring AI, Tika, Tesseract, readability4j, library version | `tooling` |
| 그 외 | `other` |

## 예시 — 트리거 B 시나리오

### 시나리오 1: Spring AI bean 생성 실패
```
1. backend-engineer 가 ./gradlew bootRun 실행
2. BeanCreationException: spring.ai.ollama.base-url 누락
3. lessons-learned 스킬 트리거:
   a. grep "spring.ai\|OllamaChatModel" docs/lessons-learned/ → 결과 없음
   b. LL-0001 작성: "환경별 application.yml 의 spring.ai 섹션 누락"
   c. 재발 방지: spec-check 스킬에 "모든 application-*.yml 에 ai.ollama 섹션 존재" 검증 추가
   d. README 인덱스 갱신
   e. 임시 해결 (application-local.yml 갱신) + 영구 해결 (requirements/02 예시 갱신)
```

### 시나리오 2: 작업 시작 시 참조
```
1. backend-engineer 가 새 task: "Tika + Tesseract 통합" 받음
2. 작업 시작 전 lessons-learned 스킬 트리거:
   a. grep "Tika\|Tesseract\|OCR" docs/lessons-learned/ → LL-0007 발견
   b. LL-0007 "비슷한 작업에 적용할 규칙" 섹션 적용:
      - "Tesseract 통합 시 항상 OS 패키지(tesseract-ocr-kor) 사전 설치 확인"
   c. Dockerfile 작성 시 위 규칙 따름
   d. task description 에 "LL-0007 참고" 인용
```

## 참고

- 디렉토리: `docs/lessons-learned/`
- 인덱스: `docs/lessons-learned/README.md`
- 템플릿: `docs/lessons-learned/0000-template.md`
- 관련 스킬: `spec-check` (재발 방지 검증 패턴 추가 통합)
- 관련 정책: `CLAUDE.md` 의 "새 작업 시 절차" 섹션

ADR ≠ LL. **ADR 은 의도된 결정(immutable), LL 은 의도치 않은 실패(append-only)**.
