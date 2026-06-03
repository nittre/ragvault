# 결정·기록 정책 — ADR · Lessons Learned

> ADR(의도된 결정, immutable) ≠ LL(의도치 않은 실패, append-only).

## ADR 정책 (반자동)

### 무엇
**Architecture Decision Records** — 옵션 결정, 모순 해결, 새 기술/도구/정책 채택을 immutable 기록으로 남긴다.

위치: `docs/adr/`
스킬: `.claude/skills/adr-propose/skill.md`

### 정책
1. **반자동 생성** — 에이전트가 결정 후보 감지 시 사용자에게 ADR 제안. 사용자 승인 후 생성.
2. **단일 출처** — ADR 은 결정의 권위 출처. `requirements/` 와 충돌하면 ADR 우선이며 requirements 갱신은 후속 작업.
3. **불변(Immutable)** — Accepted 된 ADR 은 수정하지 않는다. 변경이 필요하면:
   - 새 ADR 작성 → `Supersedes ADR-NNNN`
   - 기존 ADR 의 상태를 `Superseded by ADR-MMMM` 으로 갱신 (메타데이터만)
4. **번호 부여** — `NNNN-kebab-case-slug.md` (4자리 zero-pad)

### 결정 키워드 (트리거)
- "옵션 X 로 가자", "이렇게 결정", "확정", "최종", "이 방향"
- 두 문서 간 모순 해결 합의
- 새 라이브러리·서비스 채택
- 비용·일정·임계값·SLA 숫자 확정

### 트리거 안 함
- 단순 질문, 설명 요청
- 검토 중·미결정 상태
- 이미 ADR 에 있는 결정 재인용

### 작성 절차
1. `adr-propose` 스킬 호출 → ADR 후보 감지 + 사용자 승인 요청
2. 승인 시 `docs/adr/NNNN-slug.md` 생성 (`0000-template.md` 형식)
3. `docs/adr/README.md` 인덱스 표 갱신
4. 영향 받는 `requirements/` 문서 cross-link 및 갱신

### 현재 결정된 ADR (인덱스)
| ADR | 결정 |
|-----|------|
| [ADR-0001](../adr/0001-binlog-30min-gtid.md) | binlog 30분 주기 + GTID 전용 |
| [ADR-0002](../adr/0002-data-isolation-schema-ready.md) | 데이터 격리 옵션 D (스키마 미리, 정책 단순) |
| [ADR-0003](../adr/0003-alb-multi-az-mandatory.md) | ALB Multi-AZ 의무, 컴퓨트 Single AZ |
| [ADR-0004](../adr/0004-spring-ai-q4km.md) | Spring AI 전면 + Q4_K_M 양자화 |
| [ADR-0005](../adr/0005-parameter-priority-7-stage.md) | 파라미터 7단계 우선순위 + Guard 분리 |

추가 backfill 후보: [`docs/adr/README.md`](../adr/README.md) 참고.

---

## Lessons Learned 정책 (append-only)

### 무엇
**LL** — 에러·실패·예상치 못한 행동의 기록. **목적: 같은 실수를 반복하지 않는다.**

위치: `docs/lessons-learned/`
스킬: `.claude/skills/lessons-learned/skill.md`

### 작업 시작 시 — 참조 (의무)
모든 task 시작 직전 도메인 키워드로 `docs/lessons-learned/` grep. 발견된 LL 의 "비슷한 작업에 적용할 규칙" 우선 적용.

```bash
# 예
grep -rln "spring.ai\|OllamaChatModel" docs/lessons-learned/
grep -rln "terraform\|aws_lb" docs/lessons-learned/
grep -rln "binlog\|GTID" docs/lessons-learned/
grep -rln "PII\|access_groups" docs/lessons-learned/
```

### 에러 발생 시 — 기록 (의무)

#### 작성 트리거
- 빌드·테스트 실패 (`./gradlew build` 등)
- 런타임 에러 (NPE, BeanCreationException 등)
- 인프라 적용 실패 (`terraform apply`, `helm install`)
- 가드레일에 의해 차단 + 사용자 거부
- 통합 경계면 mismatch
- 디버깅 시간 30분+ 영역

#### 작성 안 함
- 단순 오타·즉시 수정되는 문법 오류
- 이미 동일 패턴의 LL 존재 (참조만)
- 의도된 test fail (TDD red 단계)

#### 작성 절차
1. 에러 메시지·명령어·시간 순서 즉시 기록 (메모리 신선할 때)
2. `lessons-learned` 스킬 호출 → 중복 LL 검색
3. 없으면 `docs/lessons-learned/NNNN-slug.md` 작성 (`0000-template.md` 형식)
4. `docs/lessons-learned/README.md` 인덱스 표 갱신
5. **재발 방지는 시스템·자동화로** — `spec-check` 에 새 회귀 검증 패턴 추가
6. 관련 ADR 에 `관련 LL: LL-NNNN` cross-link

### 작성 원칙
| 원칙 | 설명 |
|------|------|
| 즉시 작성 | 에러 직후. "나중에"는 잊는다. |
| 근본 원인 | 표면 증상이 아니라 진짜 원인 (5 Whys) |
| 재발 방지는 시스템 | 사람 약속 X. 자동 검증·체크리스트·코드 변경 O |
| 단정형 규칙 | "X 를 본다 → Y 한다" 형식 |
| 시간 순서대로 | 추측 말고 실제로 일어난 순서 |
| 자체 완결성 | 외부 컨텍스트 없이 6개월 후 이해 가능 |

### 카테고리
| 카테고리 | 영역 |
|---------|------|
| `build` | Gradle·Maven·dependency·classpath |
| `runtime` | Spring Boot 실행·beans·application context |
| `infra` | Terraform·k3s·Helm·AWS |
| `data` | DB 스키마·마이그레이션·동기화 |
| `security` | PII·인증·SSRF·access_groups |
| `integration` | 경계면 (Spring Boot ↔ Open WebUI 등) |
| `tooling` | Spring AI·Tika·Tesseract 등 라이브러리 |
| `other` | 위에 없는 경우 |

---

## ADR vs LL — 차이

| 축 | ADR | LL |
|----|-----|-----|
| 본질 | 의도된 결정 | 의도치 않은 실패 |
| 가변성 | Immutable (supersede 만) | Append-only (새 발견은 새 LL) |
| 시점 | 결정 시점 | 사건 발생 직후 |
| 가치 | "왜 X 를 채택했는가" | "어떤 함정을 피해야 하는가" |
| 단일 출처 책임 | 권위 출처 | 회귀 방지 자동화 트리거 |

## 참고

- 템플릿: [`docs/adr/0000-template.md`](../adr/0000-template.md), [`docs/lessons-learned/0000-template.md`](../lessons-learned/0000-template.md)
- 스킬: `.claude/skills/adr-propose/`, `.claude/skills/lessons-learned/`, `.claude/skills/spec-check/`
- 관련 정책: [team-and-workflow.md](team-and-workflow.md), [engineering-conventions.md](engineering-conventions.md)
