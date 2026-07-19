# RAG 경로에서 원본 응답 저장이 PII 마스킹 이후에 일어남 (ADR-0010 불변식 위반)

**상태**: 미해결 — 수정 계획 수립됨, 착수 전
**연관 문서**: [ADR-0010](../adr/0010-response-raw-storage.md), [ADR-0008](../adr/0008-pii-masking-all-response-paths.md), [개발자 매뉴얼 §3-7](../manual/developer-manual.md#3-7-pii-마스킹)
**이 문서가 만들어진 배경**: 루트 `README.md`를 코드와 대조 검증하던 중, README의 질의 파이프라인 다이어그램이 "`ResponseRawStorageService` 원본 저장(ADR-0010) → `PiiMasker` 마스킹(ADR-0008)" 순서를 명시하고 있는데, 실제 RAG 의도 경로만 이 순서를 지키지 않는 것을 발견했다.

## 문제

`ResponseRawStorageService.store(...)`(`app-internal/src/main/java/com/ragservice/rag/service/ResponseRawStorageService.java:35`)의 자바독은 "`piiMasker.mask()` 호출 전에 반드시 호출해야 함"이라고 명시한다. 이 불변식은 PII 마스킹이 정규식 기반이라 마스킹 실패(오탐/미탐) 가능성이 상존하기 때문에, 사고 대응 시 마스킹 전 원본을 짧게(TTL 30분) 조회해 실제로 마스킹이 빠졌는지 진단하기 위한 것이다(ADR-0010).

- **정상 동작(SQL/HYBRID)**: `TextToSqlService.java`(265→289행), `HybridQueryService.java`(127→130행) 모두 같은 메서드 안에서 `rawStorage.store(rawResponse, ...)` → `piiMasker.mask(rawResponse)` 순서를 지킨다.
- **버그(RAG)**: `RagService.chat()`(`RagService.java:147`)이 LLM 응답을 받은 직후 자체적으로 `piiMasker.mask(llmResponse)`를 호출해 **마스킹된 텍스트만** `RagResult.content()`로 반환한다. 그런데 호출자인 `QueryRouterService`의 세 지점 — `routeForceRag`(154행), `routeForceWeb`(194행), `ragWithWebFallback`(401행) — 은 이 이미 마스킹된 `rag.content()`를 `rawStorage.store(rag.content(), "RAG", userEmail, llmModel)`에 그대로 넘긴다.

결과적으로 RAG 의도로 분류/강제된 모든 질의는 Redis의 `resp_raw:*` 슬롯에 **이미 마스킹된 텍스트**가 저장된다. RAG 경로에서 PII 마스킹이 실패하더라도, 사고 대응 담당자가 "원본"을 조회해도 마스킹된(즉 이미 안전한 것처럼 보이는) 텍스트만 보게 되어 마스킹 실패를 진단할 방법이 없다 — ADR-0010이 존재하는 목적 자체가 RAG 경로에서는 무력화된 상태다.

## 왜 지금 안 고쳤나

사용자 요청으로 원인 분석과 수정 계획 수립까지만 진행하고 실제 코드 변경은 별도 세션으로 미뤘다. 계획 개요:

1. `RagService`에 `ResponseRawStorageService`와 LLM 모델명(`@Value`)을 주입.
2. `RagService.chat()`에 `userEmail` 파라미터를 추가하고, LLM 응답을 받은 직후·`piiMasker.mask()` 호출 전에 `rawStorage.store(llmResponse, "RAG", userEmail, llmModel)`을 호출해 `responseId`를 얻는다.
3. `RagResult` record에 `responseId` 필드 추가(청크 없음/차단 케이스는 LLM 호출 자체가 없어 null 유지).
4. `QueryRouterService`의 세 호출부에서 중복·오류가 있는 `rawStorage.store(rag.content(), ...)` 호출을 제거하고 `rag.responseId()`를 그대로 사용.
5. 관련 테스트 보강 — 현재 `RagServiceTest.java`에는 `rawStorage` mock이 없고, `QueryRouterServiceTest.java`는 `rawStorage.store(...)`를 스텁만 할 뿐 "무엇이 저장됐는지"(마스킹 전 원문인지)는 검증하지 않는다. store 인자가 마스킹 전 원문인지 확인하는 테스트 케이스 신설 필요.

## 영향 범위

- `app-internal/src/main/java/com/ragservice/rag/service/RagService.java`
- `app-internal/src/main/java/com/ragservice/rag/service/QueryRouterService.java` (호출부 3곳: `routeForceRag`, `routeForceWeb`, `ragWithWebFallback`)
- 테스트: `RagServiceTest.java`, `QueryRouterServiceTest.java`
- API 계약 변화 없음(응답 형태 동일, `responseId`는 이미 같은 방식으로 생성되던 값) — 낮은 리스크의 보안/진단 기능 수정.
- `app-widget`은 `ResponseRawStorageService` 개념 자체가 없어 이 이슈와 무관.

## 제안하는 다음 단계

1. 위 계획대로 `RagService`/`QueryRouterService` 수정.
2. `RagServiceTest`에 `rawStorage` mock 추가, store 인자가 마스킹 전 원문(PII 패턴 포함)인지 검증하는 테스트 추가.
3. 실제 PII 패턴이 포함된 RAG 질의로 수동 검증 — Redis `resp_raw:*`에는 원문이, 클라이언트 응답에는 마스킹된 텍스트가 들어가는지 확인.
