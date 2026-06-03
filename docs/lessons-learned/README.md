# Lessons Learned

RAG 시스템 프로젝트에서 발생한 에러·실패·예상치 못한 행동을 기록한다.
**목적**: 같은 실수를 반복하지 않는다.

## 정책

### 언제 작성하는가
- 빌드·테스트 실패 (`./gradlew build` 등 명령이 예상과 다른 결과)
- 런타임 에러 (실행 중 예외, 잘못된 응답)
- 인프라 적용 실패 (`terraform apply`, `helm install` 등)
- 보안 사고 또는 사고 직전 (가드레일에 의해 차단된 위험 명령 포함)
- 통합 경계면 mismatch (Spring Boot API ↔ Open WebUI client 등)
- 시간을 30분 이상 소비한 디버깅 (블로커가 큰 영역 — 다음에 빨리 풀어야 함)

### 언제 작성 안 하는가
- 단순 오타·문법 오류 (즉시 수정)
- 이미 LL-NNNN 에 기록된 동일 패턴 (참조만)
- "아 까먹었네" 수준의 trivial 누락

### 작성 시점
- **에러 발생 직후** (메모리 신선할 때)
- 늦어도 **해당 task 종료 전**
- 다음 PR 로 미루지 말 것 (잊혀짐)

### 작성 절차
1. `docs/lessons-learned/NNNN-slug.md` 생성 (다음 번호)
2. `0000-template.md` 형식 따름
3. 이 README 의 인덱스 표 갱신
4. 관련 ADR · requirements 가 있으면 cross-link
5. `재발 방지` 섹션은 **시스템·자동화**로 작성 (사람 약속 X)

### 작업 시작 시 참조 의무
새 작업 시작 전 에이전트는 **관련 카테고리의 lessons-learned 를 grep** 한다.

```bash
# 예: Spring AI 관련 작업 시작 전
grep -rln "spring.ai\|OllamaChatModel\|ChatClient" docs/lessons-learned/

# 예: Terraform 작업 시작 전
grep -rln "terraform\|aws_lb\|aws_rds" docs/lessons-learned/

# 예: binlog 동기화 작업 시작 전
grep -rln "binlog\|GTID\|BinaryLogClient" docs/lessons-learned/
```

발견된 LL 의 "비슷한 작업에 적용할 규칙" 섹션을 우선 따른다.

## 작성 원칙

| 원칙 | 설명 |
|------|------|
| **시간 순서대로** | 추측 말고 실제 일어난 순서 (명령어·에러 메시지 그대로) |
| **근본 원인** | 표면 증상이 아니라 진짜 원인 (5 Whys) |
| **재발 방지는 시스템** | "다음엔 조심하자"가 아니라 "spec-check 에 검증 추가" 같은 자동화 |
| **단정형** | "이렇게 했어야 했다"가 아니라 "이렇게 한다" |
| **자체 완결성** | 6개월 후 다른 사람이 읽어도 이해 가능하게 |

## 카테고리

| 카테고리 | 영역 |
|---------|------|
| `build` | Gradle·Maven·dependency·classpath |
| `runtime` | Spring Boot 실행·beans·application context |
| `infra` | Terraform·k3s·Helm·AWS |
| `data` | DB 스키마·마이그레이션·동기화 |
| `security` | PII·인증·SSRF·access_groups |
| `integration` | 경계면 (Spring Boot ↔ Open WebUI, Spring Boot ↔ Ollama 등) |
| `tooling` | Spring AI·Tika·Tesseract·readability4j 등 라이브러리 |
| `other` | 위에 없는 경우 |

## 인덱스

| # | 제목 | 카테고리 | 심각도 | 작성일 |
|---|------|---------|--------|--------|
| [0001](0001-pii-masking-spec-check-gap.md) | spec-check 가 모든 LLM 응답 경로 PII 마스킹 회귀 검증을 누락 | security | CRITICAL | 2026-05-20 |
| [0002](0002-spring-boot35-spring-ai10-compat.md) | Spring Boot 3.5 + Spring AI 1.0.0 — 아티팩트명·테스트 API 호환성 변경 | tooling/build | HIGH | 2026-05-21 |
| [0003](0003-hook-relative-path-cwd.md) | PreToolUse Hook 상대경로 — CWD 변경 시 Hook 중단 | other | HIGH | 2026-05-21 |
| [0004](0004-docker-healthcheck-no-curl.md) | Docker healthcheck — 최소 이미지(Alpine/Go binary)에 curl 없음 | infra | MEDIUM | 2026-05-21 |
| [0005](0005-enableasync-proxy-target-class.md) | @EnableAsync 기본값 JDK 프록시 → @Async 빈 타입 불일치 (proxyTargetClass=true 필수) | runtime | HIGH | 2026-05-28 |
| [0006](0006-pgvector-jsqlparser-native-query.md) | pgvector <=> && CAST 연산자 → JSqlParserQueryEnhancer 파싱 실패 → EntityManager 직접 사용 | data | HIGH | 2026-05-28 |

## 참고

- 작성·참조 자동화: `.claude/skills/lessons-learned/skill.md`
- 형식 참고: 토요타 카이젠 / Atul Gawande "Checklist Manifesto" / Postmortem culture
- ADR (architectural decisions, immutable) ≠ LL (operational failures, append-only)
