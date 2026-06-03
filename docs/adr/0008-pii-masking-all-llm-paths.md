# ADR-0008: PII 마스킹 — 모든 LLM 응답 경로 적용 (ADR-0007 확장)

- **상태**: Accepted
- **결정일**: 2026-05-20
- **결정자**: 시니어 UX 리서처 발견 → 시니어 백엔드 엔지니어 결정
- **관련 ADR**: ADR-0007 (SQL Layer 1+3 의 일반화)
- **영향 받는 문서**: `requirements/04-rag-search-strategy.md`, `requirements/05-prompt-design.md`, `requirements/08-text-to-sql.md`, `requirements/10-multimodal-files-url.md`, `docs/policies/security-and-guardrails.md`

## 컨텍스트

ADR-0007 은 **SQL 자연어화 응답에만 Layer 3 PII 마스킹** 을 명시했다. 그러나 시니어 UX 리서처가 End User Journey S7 (이미지 첨부) 점검 중 다음 사실을 발견:

### 발견 — VLM 응답에 PII 포함 가능

```
[시나리오]
사용자가 화이트보드 사진 첨부:
"홍길동 010-1234-5678 안건 발의" 라고 손글씨 적혀있음

qwen2.5-vl 응답:
"화이트보드에는 홍길동(010-1234-5678)의 안건 발의가 적혀있습니다..."

→ VLM 응답에 PII 그대로 포함
→ ADR-0007 의 SQL Layer 3 PII 마스킹은 SQL 경로에만 적용 → IMAGE 경로 누락
```

### 확장된 위험 영역

같은 위험이 다른 경로에도 존재:

| 경로 | PII 노출 시나리오 |
|------|----------------|
| **RAG** | 동기화 시점 PII 마스킹 적용되지만, 마스킹 정규식이 놓친 PII (예: 이름·주소 — Phase 0 정규식 비대상) 가 청크에 남아 LLM 답변에 인용 |
| **HYBRID** | RAG + SQL 둘 다 → 두 경로의 PII 누설 위험 모두 누적 |
| **URL_FETCH** | 외부 페이지 본문에 PII (예: 뉴스 기사 인터뷰 대상 이름·전화) → LLM 답변에 인용 |
| **FILE** | 첨부 PDF 안 PII (계약서·매뉴얼) → Tika 추출 시 마스킹되지만 OCR 결과의 PII 누락 가능 |
| **IMAGE** | VLM 이 이미지 안 글자를 자연어로 인용 (위 시나리오) |

### 본질
**LLM 자연어화 응답은 어느 경로에서 들어왔든 사용자 화면에 그대로 표시됨**. PII 마스킹은 응답 후처리 (Layer 3) 에서 전 경로에 일관 적용되어야 한다.

### ADR-0007 누락 원인
ADR-0007 작성 시점에 SQL 경로의 실시간 DB 조회 위험만 집중 분석. 다른 경로(RAG/HYBRID/URL/FILE/IMAGE)의 LLM 응답이 PII 를 인용할 수 있다는 점을 충분히 짚지 않음.

## 결정

**모든 의도 경로(RAG / SQL / HYBRID / URL_FETCH / FILE / IMAGE) 의 LLM 자연어화 응답에 Layer 3 PII 마스킹 일관 적용**.

### 구체 적용
```java
// 통합 정책 — 모든 경로의 응답 반환 직전
String llmResponse = chatClient.prompt()...call().content();

// 또는 SSE 스트리밍 응답을 토큰별 또는 완성 후 마스킹
String safeResponse = piiMasker.mask(llmResponse, PiiMaskingLevel.STANDARD);

if (!safeResponse.equals(llmResponse)) {
    metrics.increment("rag_pii_masked_total",
        Tag.of("path", queryIntent.name())  // RAG / SQL / HYBRID / URL / FILE / IMAGE 별 카운트
    );
}
return safeResponse;
```

### SSE 스트리밍 처리
- **옵션 A (권장)**: 완성 후 마스킹 — 전체 답변 수신 후 한 번에 마스킹 → 사용자에게 발송. 스트리밍 효과 X 지만 PII 누설 0
- **옵션 B**: 토큰별 마스킹 — 토큰 도착마다 마스킹 → 사용자에게 즉시 스트리밍. 스트리밍 유지 but 부분 PII 누설 가능 (예: `01` 도착 → 안전 → `0-1234` 도착 → 마스킹 가능, 그러나 사용자는 이미 `0` 봄)

→ **PII 마스킹은 옵션 A (완성 후) 채택**. 응답시간 약간 증가 (~5ms ~ ~50ms 추가) 하지만 보안 우선.
→ 단, SSE 자체 패턴은 유지 (각 답변마다 한 SSE 스트림 — 클라이언트 호환성).

### Layer 1 (생성 차단) — SQL 만 적용 유지
ADR-0007 의 Layer 1 (SQL Validator + 컬럼 화이트리스트) 은 SQL 특유의 위험이라 SQL 경로에만 유지. **다른 경로엔 Layer 1 적용 안 함** (RAG 검색은 access_groups 필터로 별도 통제).

## 결과

### 장점
- 모든 경로 PII 마스킹 일관 — 사용자에게 보이는 응답은 어디서 왔든 안전
- 메트릭 분리 (`rag_pii_masked_total{path}`) → 어느 경로에서 PII 가 자주 잡히는지 운영 데이터 확보
- ADR-0007 의 자연스러운 확장 — 기존 `PiiMasker` 자원 그대로 재사용
- Phase 1+ Llama Guard / Presidio 도입 시 일괄 격상 가능

### 단점·트레이드오프
- SSE 스트리밍 효과 일부 손실 (완성 후 마스킹 = 토큰별 표시 불가)
- 응답시간 +5~50ms (정규식 처리, 답변 길이 의존)
- 정규식 false positive (예: 카드번호 같은 16자리 숫자열) → Phase 1+ NER 로 정밀화

### 후속 작업
- `requirements/04-rag-search-strategy.md` 섹션 2 검색 흐름 [12] 단계에 PII 마스킹 명시 추가
- `requirements/05-prompt-design.md` 응답 후처리 섹션에 명시 추가
- `requirements/10-multimodal-files-url.md` URL/FILE/IMAGE 경로 응답 후처리에 명시 추가
- `docs/policies/security-and-guardrails.md` "핵심 보안 원칙 1" PII 마스킹 항목에 "모든 경로 적용" 명시 (이미 있음 → 강화)
- **`.claude/skills/spec-check/skill.md`** 회귀 검증 패턴 추가:
  ```bash
  # 모든 LLM 응답 경로에 PII 마스킹 적용 확인
  grep -rn "chatClient.prompt\|chatClient.call\|.call().content()" \
       rag-backend/src/main/java/ | grep -v "piiMasker.mask\|safeResponse"
  # 결과 = 0 (모든 응답이 마스킹 통과) 이어야 BLOCKER 아님
  ```
- code-reviewer 가 새 LLM 호출 코드 리뷰 시 회귀 검증 강제
- LL-0001 작성 (spec-check 회귀 검증 누락 자체)

## 대안

### 옵션 B — 토큰별 마스킹 (스트리밍 유지)
SSE 스트리밍 효과 유지. 그러나 부분 PII 누설 가능 (예: `010-1234`까지 사용자가 본 뒤 다음 토큰에서 마스킹). 거부.

### 옵션 C — SQL 만 마스킹 유지 (ADR-0007 그대로)
가장 단순하나 IMAGE/URL/FILE 의 PII 누설 위험 그대로. 거부.

### 옵션 D — 완전 비결정론적 LLM-as-Judge
별도 LLM 호출 (~500ms~2s) 로 PII 검출. 비용·결정성·false negative 위험. Phase 0 부적합. ADR-0007 에서 이미 거부.

## 참고

- 권위 출처: `requirements/04`, `05`, `08`, `10` (각 경로 응답 후처리)
- 정규식 패턴: `requirements/03-data-sync-pipeline.md` 섹션 5 `PiiMasker`
- 관련 LL: LL-0001 (spec-check 회귀 검증 누락)
- 관련 메트릭: `rag_pii_masked_total{path}`
- Phase 1+ 격상 후보: Llama Guard / Presidio (NER 기반 의미 단위 PII 검출)
