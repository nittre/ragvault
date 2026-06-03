# LL-0001: spec-check 가 모든 LLM 응답 경로 PII 마스킹 회귀 검증을 누락했다

- **발생일**: 2026-05-20
- **작성자**: 시니어 UX 리서처 (S7 점검 중 발견) + 시니어 백엔드 엔지니어 (기록)
- **카테고리**: security
- **심각도**: CRITICAL
- **관련 작업**: User Journey 점검 S7 (이미지 첨부), `docs/ux/user-journeys.md`
- **관련 ADR / Requirements**: ADR-0007 → ADR-0008 (확장), `requirements/08-text-to-sql.md` 섹션 12, `requirements/04`, `requirements/05`, `requirements/10`

## 에러 상황 (What happened)

End User Journey S7 (이미지 첨부 시나리오) 점검 중 다음 시나리오를 그렸다:

```
1. 사용자가 화이트보드 사진 첨부 — 손글씨로 "홍길동 010-1234-5678 안건 발의"
2. 의도 분류기 → IMAGE 경로
3. qwen2.5-vl:7b (VLM) 호출
4. VLM 답변: "화이트보드에는 홍길동(010-1234-5678)의 안건 발의가 ..."
5. Spring Boot → SSE 스트리밍 → 사용자 화면
→ PII (이름·전화번호) 그대로 노출
```

같은 시점에 시스템의 PII 마스킹 정책을 확인:
- ADR-0007: **SQL 자연어화 응답에만** Layer 3 PII 마스킹 적용
- `requirements/08-text-to-sql.md` 섹션 12: SQL 경로 한정으로 명시
- RAG / HYBRID / URL_FETCH / FILE / IMAGE 경로의 LLM 응답 후처리는 명시 부재

확인:
- `requirements/04`, `05`, `10` — 응답 후 PII 마스킹 명시 없음
- `docs/policies/security-and-guardrails.md` — "PII 마스킹 일관 적용" 추상 원칙은 있으나 **모든 경로에 자동 적용된다는 검증 명시 없음**
- `.claude/skills/spec-check/skill.md` — SQL Layer 1+3 회귀 검증은 있으나 **다른 경로 검증 패턴 없음**

→ 즉, **시스템 결함**이 결정 단계부터 존재했고, **회귀 검증 자동화도 누락**되어 코드 작성 시점에 잡힐 수 없었음.

## 원인 (Root cause)

### 표면 증상
ADR-0007 작성 시점에 SQL 경로 한정으로만 명시.

### 5 Whys
1. **왜 SQL 만 명시?** — ADR-0007 작성 시 사용자가 "SQL PII 마스킹" 영역만 짚어서 결정 요청
2. **왜 다른 경로도 짚지 않았나?** — 시니어 백엔드가 SQL 의 실시간 DB 조회 위험만 분석. **VLM·URL·File 응답이 PII 인용 가능** 하다는 점을 충분히 발굴 못 함
3. **왜 발굴 못 했나?** — 보안 분석 시 "데이터 소스" 관점만 봄. **응답 출력** 관점은 누락
4. **왜 spec-check 도 잡지 못했나?** — spec-check 의 회귀 검증 패턴이 SQL 코드만 grep. **`chatClient.prompt` 전반에 PII 마스킹이 적용되는지** 같은 일반화된 검증 패턴 부재
5. **왜 spec-check 가 일반화 안 됐나?** — `spec-check` 작성 시점에 ADR-0007 만 봤고, "모든 LLM 응답" 같은 추상 원칙을 회귀 검증으로 자동화하지 않음

### 진짜 원인 — 두 가지
1. **ADR 작성 시 영역 분석이 좁음** — SQL 위험만 짚었고, 응답 출력 일반화 누락
2. **spec-check 가 ADR 구체 검증만 하고 추상 원칙 검증을 누락** — `docs/policies/security-and-guardrails.md` 의 "PII 마스킹 일관 적용" 같은 원칙이 자동 검증 패턴으로 번역되지 않음

## 해결 (Resolution)

### 임시
시니어 UX 리서처가 S7 점검 중 즉시 발견 → 사용자에게 escalate → ADR-0008 결정.

### 영구
1. **ADR-0008 작성** — 모든 LLM 응답 경로에 Layer 3 PII 마스킹 일관 적용
2. **`spec-check` 회귀 검증 패턴 추가**:
   ```bash
   grep -rn "chatClient.prompt\|chatClient.call\|.call().content()" \
        rag-backend/src/main/java/ \
        | grep -v "piiMasker.mask\|safeResponse\|@TestOnly"
   ```
   결과 = 0 이어야 BLOCKER 아님
3. **`requirements/04`, `05`, `10`** 각 경로 응답 후처리 단계에 PII 마스킹 명시 추가 (구현 시점에)
4. **메트릭 추가**: `rag_pii_masked_total{path}` (RAG/SQL/HYBRID/URL/FILE/IMAGE 별)
5. **추상 원칙 → 자동 검증 변환 체크리스트 도입**

## 재발 방지 (Prevention)

### 시스템 변경
1. **`spec-check` 강화**
   - SQL Layer 1+3 검증 외에 모든 LLM 응답 경로 회귀 검증 추가
   - `docs/policies/security-and-guardrails.md` 의 "핵심 보안 원칙" 10개 각각을 자동 grep 패턴으로 변환

2. **ADR 작성 절차 보강 (`.claude/skills/adr-propose/skill.md`)**
   - ADR 작성 시 "이 결정이 다른 경로 / 다른 영역에도 적용되어야 하는가?" 체크리스트 추가
   - 예: "이 결정이 RAG / SQL / HYBRID / URL / FILE / IMAGE 6경로 중 어디에 적용되나?" 명시 요구

3. **code-reviewer 가이드 보강 (`.claude/agents/code-reviewer.md`)**
   - "추상 보안 원칙이 실제 코드 모든 경로에 적용되는지" 회귀 검증을 BLOCKER 카테고리에 추가
   - PII·access_groups·SSRF 등 추상 원칙별 grep 패턴 정리

### 자동화
4. **CI/CD 단계에 spec-check 통합** — Phase 1+ Jenkins 파이프라인 stage 추가 (구현 시점)

## 비슷한 작업에 적용할 규칙 (When you see X, do Y)

미래 에이전트·인간 작업자가 비슷한 패턴을 만났을 때:

### 규칙 1 — "특정 경로" ADR 작성 시
- ADR 작성 → "이 결정이 시스템의 **다른 경로** 에도 동일하게 적용되어야 하는가?" 자문
- 예: "SQL PII 마스킹" → "RAG/HYBRID/URL/FILE/IMAGE 도?"
- 적용 시 ADR 본문에 "**전 경로 적용 의무**" 명시 또는 "특정 경로 한정" 명시

### 규칙 2 — 추상 보안 원칙 작성 시
- `docs/policies/` 의 추상 원칙 (예: "PII 마스킹 일관 적용") 작성 시 **즉시** 자동 검증 patterns (`grep` / 정적 분석) 작성
- 추상 원칙은 자동 검증 없이는 회귀 발생 보장

### 규칙 3 — code-reviewer 가 새 LLM 호출 코드 리뷰 시
- `chatClient.prompt` / `chatClient.call` 같은 신규 호출 발견 시
- **PII 마스킹이 응답 직전에 적용되는지** 회귀 검증 (BLOCKER 카테고리)

### 규칙 4 — verifier 가 검증 시
- 새 의도 경로 추가 시 (Phase 1+ 에 새 경로 도입 가능성)
- `rag_pii_masked_total{path=NEW_PATH}` 메트릭이 작동하는지 E2E 검증

### 규칙 5 — UX 리서처 / End User 시나리오 점검 시
- 모든 응답 시나리오에서 "이 답변에 PII 가 포함된 시뮬레이션" 시도
- 각 경로 (RAG/SQL/HYBRID/URL/FILE/IMAGE) 별 PII 누설 시나리오 1개씩 시뮬레이션

## 참고

- 관련 ADR: [ADR-0007 SQL Layer 1+3](../adr/0007-sql-pii-layer-1-3.md) → [ADR-0008 전 경로 확장](../adr/0008-pii-masking-all-llm-paths.md)
- 영향 받는 requirements: `04`, `05`, `08`, `10`
- 영향 받는 정책: `docs/policies/security-and-guardrails.md`
- 영향 받는 스킬: `.claude/skills/spec-check/skill.md`, `.claude/skills/adr-propose/skill.md`
- User Journey 발견 위치: `docs/ux/user-journeys.md` S7 시나리오
