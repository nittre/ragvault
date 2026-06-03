# ADR-0007: SQL 결과 PII 마스킹 — Layer 1 (생성 차단) + Layer 3 (응답 후처리)

- **상태**: Accepted
- **결정일**: 2026-05-19
- **결정자**: 시니어 백엔드 엔지니어 (사용자 옵션 D 채택)
- **관련 ADR**: ADR-0002 (데이터 격리)
- **영향 받는 문서**: `requirements/08-text-to-sql.md` 섹션 6·8·12, `requirements/03-data-sync-pipeline.md` 섹션 5 (PiiMasker 재사용)

## 컨텍스트

`requirements/08-text-to-sql.md` 의 보안 정책에 한 줄만 있었다:
```
[6] PII 자동 마스킹
- 결과에 PII 컬럼 포함되면 마스킹
- 자연어화 후에도 한 번 더 검증
```

→ **어떻게 마스킹하고 어떻게 검증하는지 어디에도 명시 없음**.

### Text-to-SQL 의 PII 노출 경로 (RAG 와 결정적 차이)
- RAG: 동기화 시점에 마스킹 → 검색 시점엔 안전
- **SQL**: 실시간 DB 조회 → 마스킹 안 된 원본이 LLM 컨텍스트에 들어감 → 응답에 PII 그대로 노출 위험

### 구체 위험 시나리오
```
사용자: "홍길동 고객 정보 알려줘"
LLM 생성 SQL: SELECT * FROM customers WHERE name = '홍길동'
→ SqlValidator 가 SELECT * 차단 안 되면 모든 컬럼 노출
→ LLM 자연어화 → "홍길동 연락처는 010-XXXX, 주민번호는 ..."
```

또한 `excluded_columns` 만으론 부족:
- LLM 프롬프트에 스키마 노출만 막을 뿐, 사용자가 직접 `SELECT phone FROM ...` 요청하면 차단 안 됨
- `allowed_columns` 가 NULL(전체) 이거나 admin 실수로 phone 포함 시 노출

## 결정

**2중 방어 — Layer 1 (생성·검증 차단) + Layer 3 (응답 후처리 마스킹)**.

### Layer 1 — SQL 생성·검증 단계 (SqlValidator)
1. **`SELECT *` 거부**
   ```java
   if (hasSelectStar(select)) {
       return ValidationResult.deny("SELECT * 는 허용되지 않습니다. 명시적 컬럼 목록 사용.");
   }
   ```
2. **컬럼 화이트리스트 검증**
   - SELECT 절·WHERE 절·ORDER BY 절에서 사용된 모든 컬럼 추출
   - `sql_table_config.excluded_columns` 에 포함된 컬럼이 등장하면 거부
3. **테이블 화이트리스트** (기존 유지)

### Layer 3 — LLM 자연어화 응답 후처리
```java
String llmResponse = chatClient.prompt()
    .system(synthesizePrompt)
    .user(...)
    .call().content();

String safeResponse = piiMasker.mask(llmResponse, PiiMaskingLevel.STANDARD);
// PiiMasker 는 03-data-sync-pipeline.md 섹션 5 의 동일 정규식 재사용

if (!safeResponse.equals(llmResponse)) {
    metrics.increment("rag_sql_pii_masked_total");  // Layer 1 누락 감지용
}
return safeResponse;
```

### 채택 안 한 옵션
- **Layer 2 — 결과 행 단위 마스킹**: 코드 복잡도 큼 (타입 다양·정규식 false positive). Layer 1+3 로 충분.
- **LLM-as-Judge**: 추가 LLM 호출 (응답시간 +500ms~2s, GPU 부하), false negative 위험, 결정성 부족. Phase 0 부적합.
- **RAGAS**: PII 검출 도구 아님 (faithfulness 평가 도구). 오용.

## 결과

### 장점
- Layer 1 이 99% 차단, Layer 3 가 잔불 끄는 안전망 (defense in depth)
- 추가 LLM 호출 0 — 응답시간 영향 < 5ms
- 결정론적 — 같은 입력 = 같은 출력
- 기존 `PiiMasker` 정규식 재사용 — 새 코드 거의 0
- `rag_sql_pii_masked_total` 메트릭으로 Layer 1 누락 사후 감지 가능

### 단점·트레이드오프
- 정규식 기반이라 NER 단위 PII (이름·주소 일부) 못 잡음 → Phase 1+ Llama Guard / Presidio 검토 후보
- `SELECT *` 거부로 LLM 이 매번 명시적 컬럼 목록 생성해야 함 (프롬프트 품질 의존)

### 후속 작업
- `SqlValidator` 에 `hasSelectStar()` + `ColumnExtractor.extractAll()` 구현
- `SqlExecutor` 후 자연어화 단계에서 `piiMasker.mask()` 호출
- `sql_execution_log.generated_sql` 저장 시 PII 마스킹 (audit 보호)
- `spec-check` 스킬에 회귀 검증 추가: `grep "chatClient.prompt" rag-backend/ | grep -v "piiMasker.mask"` 결과 = 0
- Phase 1+ : Llama Guard 또는 Presidio 도입 → NER 단위 PII 검출

## 대안

### 옵션 A — 3중 방어 (Layer 1 + Layer 2 + Layer 3)
Layer 2 (결과 행 단위 마스킹) 추가. 더 안전하지만 코드 복잡도 큼. Phase 0 부적합.

### 옵션 B — Layer 1 만
SqlValidator 만 강화. Layer 1 누락 시 즉시 노출 (single point of failure). 거부.

### 옵션 C — PII 컬럼 자체 등록 금지 (정책)
운영 정책으로 PII 컬럼 있는 테이블 등록 금지. 가장 안전하지만 비즈니스 가치 손실 (customers 테이블 통계 자체 불가). 거부.

### 옵션 E — LLM-as-Judge / RAGAS
위 "채택 안 한 옵션" 참고. Phase 0 부적합.

## 참고

- 권위 출처: `requirements/08-text-to-sql.md` 섹션 6 (Layer 1) · 8 (Layer 3) · 12 (보안 정책 종합)
- PiiMasker 정의: `requirements/03-data-sync-pipeline.md` 섹션 5
- 데이터 격리와의 관계: ADR-0002 — `sql_table_config.data_sensitivity` / `excluded_columns` / `allowed_groups` 가드와 함께 작동
