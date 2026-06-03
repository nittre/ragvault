# LL-NNNN: 한 줄 제목 (에러 또는 실패 영역 핵심)

- **발생일**: YYYY-MM-DD
- **작성자**: (에이전트 이름 또는 사용자)
- **카테고리**: build | runtime | infra | data | security | integration | tooling | other
- **심각도**: BLOCKER | CRITICAL | MAJOR | MINOR
- **관련 작업**: (Task ID, PR, 또는 작업 단위 설명)
- **관련 ADR / Requirements**: ADR-NNNN, requirements/XX 섹션 N

## 에러 상황 (What happened)

구체 증상을 시간 순으로 기록.
- 명령어·요청·코드 변경
- 에러 메시지 (정확히, 가능하면 stack trace 포함)
- 예상과 실제의 차이

```
예시:
1. backend-engineer 가 `./gradlew build` 실행
2. Spring AI OllamaChatModel bean 생성 시 NoSuchBeanDefinitionException
3. application-local.yml 에 spring.ai.ollama.base-url 누락
```

## 원인 (Root cause)

근본 원인. 표면 증상이 아니라 진짜 원인.
- 코드·설정·환경 어느 layer 의 문제인가
- 왜 사전에 잡히지 않았는가 (테스트·리뷰 누락 등)

```
예시:
- 환경별 application-{profile}.yml 분리 시 local 파일에 ai 섹션 미포함
- 기존 requirements/02-stack-reference.md 의 application.yml 예시가 prod 만 보여줘서 local 누락이 자명하지 않았음
- 단위 테스트가 application-test.yml 사용 → local 구성 검증 안 됨
```

## 해결 (Resolution)

실제로 무엇을 했는가. 임시 우회와 영구 해결을 구분.

```
예시:
[임시]
- application-local.yml 에 spring.ai.ollama.base-url=http://localhost:11434 추가

[영구]
- requirements/02-stack-reference.md 의 application.yml 예시에 local/dev/prod 모두 포함
- @SpringBootTest 가 local profile 도 검증하도록 통합 테스트 추가
```

## 재발 방지 (Prevention)

같은 실수를 어떻게 막을 것인가. 시스템·체크리스트·자동화.

```
예시:
- spec-check 스킬에 "환경별 application.yml 모두 ai.ollama 섹션 존재" 검증 추가
- 새 환경 추가 시 4개 application-{profile}.yml 모두 갱신 체크리스트 (CLAUDE.md 자주 쓰는 명령어 섹션)
- code-reviewer 가 환경 설정 변경 시 모든 profile 갱신 여부 회귀 확인
```

## 비슷한 작업에 적용할 규칙 (When you see X, do Y)

미래의 에이전트가 비슷한 작업을 만났을 때 따라야 할 명확한 규칙.

```
예시 — 비슷한 패턴 감지 시:
- "Spring Boot 환경별 yml 변경" 작업이 보이면 → 4개 profile 모두 갱신 + spec-check 실행
- "Spring AI bean 추가" 작업이 보이면 → application.yml 의 spring.ai 섹션 존재 확인 → 누락 시 추가
- 새 라이브러리 도입 시 → local/dev/prod 환경 모두에서 설정·테스트 검증
```

## 참고

- 관련 PR / Commit: (있다면)
- 관련 ADR: ADR-NNNN
- 권위 출처: requirements/XX
