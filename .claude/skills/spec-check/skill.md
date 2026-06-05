---
name: spec-check
description: requirements/ 와 docs/adr/ 의 결정사항 정합성을 검증한다. 코드·문서 작성·수정 후 회귀 검증이 필요할 때, "요구사항대로 맞나 확인해줘", "결정사항 위반 없는지 봐줘", "spec check" 같은 요청 시 반드시 이 스킬을 호출하라. 비용·일정·임계값·양자화·타임아웃 등의 권위 출처 침범도 감지한다.
---

# Spec Check — 요구사항·ADR 정합성 검증 스킬

코드·문서 변경 후 RAG 프로젝트의 **결정사항 회귀**를 검증한다.

## 언제 트리거하는가

### Should-trigger
- "spec check", "요구사항 검증", "결정사항 위반 없는지 봐줘"
- code-reviewer / verifier 가 회귀 검증을 요청할 때
- 비용·일정·임계값을 인용하는 PR 직전
- 새 코드를 머지하기 전 (특히 RAG·SQL·인증 영역)

### Should-NOT-trigger
- 단순 문법 검사 (린트는 별도)
- 기능 테스트 (`./gradlew test` 등)
- 새 기능 설계 단계 (아직 결정 없음)

## 검증 체크리스트

### 1. 권위 출처 침범 검사 (단일 출처 원칙)

| 항목 | 권위 출처 | 다른 곳에서 위반 신호 |
|------|---------|------------------|
| Phase 0 일정 | `requirements/01-architecture.md` 섹션 11 (약 3.5~4개월) | "2개월", "2.5개월" 표기 |
| binlog 동기화 주기 | ADR-0001 (30분) | "매일 새벽 2시" 잔재 |
| 파라미터 우선순위 | ADR-0005 (7단계) | 별도 우선순위 정의 |
| 의도 분류 경로 수 | `requirements/04` + `requirements/10` (6경로) | "3경로" 표기 |

검증 명령:
```bash
# 비용 단일 출처
grep -rn "\$435\|\$400" requirements/ docs/

# 옛 binlog 표기
grep -rn "매일 새벽 2시\|binlog_file\|cron = \"0 0 2" requirements/ rag-backend/

# 일정 옛 표기
grep -n "2개월\|2\.5개월" requirements/

# 파라미터 우선순위 — 09 가 권위 출처
grep -rn "우선순위 (높음 → 낮음)\|우선순위.*4단계" requirements/04
```

### 2. ADR 정합 검사

각 ADR 의 결정이 코드·문서에 실제로 반영됐는지:

```
ADR-0001 (binlog 30분 GTID)
  □ @Scheduled(cron = "0 */30 * * * *") 존재
  □ binlog_position 스키마에 binlog_file/position 없음
  □ gtid_mode = ON 사전 조건 명시
  □ BinaryLogClient.setGtidSet() 사용

ADR-0002 (데이터 격리 옵션 D)
  □ document_chunks.access_groups 컬럼 + GIN 인덱스
  □ 모든 벡터 검색 쿼리에 `access_groups && $userGroups` 필터
  □ rag_table_config / sql_table_config 에 data_sensitivity + allowed_groups
  □ 'restricted' 등록 거부 가드

ADR-0003 (ALB Multi-AZ)
  □ Public Subnet AZ-a + AZ-c 양쪽 정의
  □ ALB "Single AZ" 표기 잔재 없음

ADR-0004 (Spring AI + Q4_K_M)
  □ Spring AI ChatClient + OllamaChatModel 사용
  □ raw OllamaClient 자체 wrapper 없음 (grep)
  □ application.yml 의 model 이 q4_K_M suffix 포함
  □ 에 qwen2.5:14b-instruct-q4_K_M + qwen2.5-vl:7b + bge-m3 사전 pull

ADR-0005 (7단계 우선순위)
  □ ParameterResolver 가 7단계 + Guard A/B 구현
  □ Guard A(클램핑)와 Guard B(강제 고정) 코드상 분리
```

### 3. 보안 회귀 검사

```bash
# PII 마스킹 누락
grep -rn "chatClient.prompt\|return.*response\|return.*content" rag-backend/src/main/java/ \
  | grep -v "piiMasker.mask\|safeResponse"
# → 마스킹 없이 반환되는 응답 경로가 있는지

# access_groups 필터 누락
grep -rn "embedding <=>" rag-backend/src/main/java/ | grep -v "access_groups"
# → 벡터 검색 중 그룹 필터 누락된 쿼리

# SSRF Guard 누락
grep -rn "HttpClient.newBuilder\|WebClient.create" rag-backend/src/main/java/ \
  | grep -v "ssrfGuard\|SsrfGuard"
# → URL fetch 코드가 가드 없이 외부 호출하는지

# 위험한 SELECT *
grep -rn "SELECT \*\|select\s\*" rag-backend/src/main/java/
# → SqlValidator 는 SELECT * 거부해야 함
```

### 4. 비용·일정 회귀 검사

```bash
# 비용 표 단일 출처 확인
# → 표의 합계가 본문 인용과 일치하는지

# Phase 0 체크리스트가 ADR 모두 반영
grep -A 30 "Phase 0 — MVP" requirements/01-architecture.md
```

### 5. 문서 cross-reference 검사

```bash
# 깨진 링크
grep -rn "\[.*\](" requirements/ docs/ \
  | python3 -c "
import re, sys, os
for line in sys.stdin:
    for m in re.finditer(r'\[[^\]]+\]\(([^)]+)\)', line):
        url = m.group(1)
        if url.startswith('http'): continue
        path = url.split('#')[0]
        if path and not os.path.exists(path):
            print(f'BROKEN: {line.split(\":\")[0]} -> {url}')
"
# → 끊긴 상대 경로 링크
```

## 출력 포맷

```markdown
## Spec Check Report

### 권위 출처 침범
- ❌ 일정 — requirements/X 에 "2.5개월" 잔재 (line N)

### ADR 정합
- ✅ ADR-0001 (binlog 30분 GTID): 모든 항목 OK
- ⚠️ ADR-0002 (데이터 격리): document_chunks 마이그레이션 누락
- ✅ ADR-0003 (ALB Multi-AZ): OK
- ❌ ADR-0004 (Spring AI): raw OllamaClient 발견 (rag-backend/.../X.java:N)

### 보안 회귀
- ❌ PII 마스킹 누락: ImagePathService.java:42 — VLM 응답 직접 반환
- ✅ access_groups 필터: 모든 벡터 쿼리 OK
- ✅ SSRF Guard: URL Fetch 경로 OK

### 비용·일정
- ✅ Phase 0 체크리스트 — 6경로 의도 분류 포함

### Cross-reference
- ❌ requirements/04.md → 10-multimodal-files-url.md#섹션-5 (앵커 없음)

### 결론
- BLOCKER: 2건 (PII 마스킹, raw OllamaClient)
- WARNING: 2건
- OK: 5건

권장: BLOCKER 수정 후 verifier 로 진행.
```

## 빠른 체크 명령어 모음

```bash
# 1. 비용·일정 회귀
grep -rn "\$435\|\$400\|2개월\|2\.5개월" requirements/ docs/ rag-backend/ rag-infra/ 2>/dev/null

# 2. binlog 옛 표기
grep -rn "매일 새벽 2시\|binlog_file VARCHAR" requirements/ rag-backend/ 2>/dev/null

# 3. raw OllamaClient
grep -rn "new OllamaClient\|private OllamaClient\|@Autowired.*OllamaClient" rag-backend/ 2>/dev/null

# 4. ALB Single AZ 잔재
grep -rn "Single AZ.*ALB\|ALB.*Single AZ" requirements/ rag-infra/ 2>/dev/null

# 5. 7단계 우선순위 일관성
grep -rn "Stage 1.*hardcoded\|Stage 6.*request" requirements/ 2>/dev/null
```

## 참고

- 권위 출처 인덱스: `docs/adr/README.md` (ADR 목록)
- 요구사항 인덱스: `requirements/TEAM-OVERVIEW.md` (전체 개요)
- 가드레일: `.claude/hooks/guardrail.py` (위험 명령 차단)

검증의 본질은 **결정사항 위반 회귀 발견**. "코드 컴파일 됐다"가 아니라 "결정대로 작동하는가".
