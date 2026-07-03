# ADR-0007: 멀티턴 RAG — 검색 쿼리 재작성 및 WEB_SEARCH 폴백

- **상태**: Accepted
- **결정일**: 2026-07-03
- **결정자**: 개발팀
- **관련 ADR**: ADR-0004
- **영향 받는 코드**: `app-internal/src/.../service/RagService.java`, `app-internal/src/.../service/QueryRouterService.java`, `app-internal/src/.../controller/ChatController.java`, `app-widget/src/.../service/WidgetRagService.java`

## 컨텍스트 (Why)

"RAG 시스템 설계할 때 고려해야할거 알려줘" → 정상 답변 후 "좀 더 자세하게 설명해줘"라고
후속 질문하면 "관련된 정보를 자료에서 찾을 수 없습니다"라는 오답 폴백이 나오는 문제가
보고됐다. 원인을 추적한 결과 세 가지 결함이 겹쳐 있었다.

1. **검색이 현재 메시지만 본다.** `RagService.chat()` / `WidgetRagService.chat()` 모두
   대화 이력(`history`)을 LLM 최종 프롬프트에는 포함하면서도, pgvector 검색을 위한
   임베딩은 항상 사용자의 마지막 메시지 원문만 사용했다. "좀 더 자세히" 같은 지시어만
   있는 후속 질문은 그 자체로는 문서와 의미적으로 유사하지 않아 threshold 이상의 청크가
   하나도 안 잡히고, LLM 호출 전에 고정 폴백 메시지로 즉시 종료된다.
2. **검색이 성공해도 답변이 안 길어진다.** 시스템 프롬프트에 "답변은 명확하고 간결하게
   작성하세요"라는 규칙이 모든 턴에 고정 적용되어 있어, "더 자세히" 요청과 정면 충돌한다.
   게다가 topK가 첫 턴·후속 턴 동일해서 재작성된 쿼리로 검색해도 대체로 같은 청크만
   다시 뽑혀 "더 참조할 자료" 자체가 늘어나지 않았다.
3. **RAG로 시작한 대화의 후속 질문이 내부 문서에 없으면 그냥 실패한다.** 예: RAG 답변에
   등장한 용어("LLM2")를 되묻는 질문은 내부 지식문서에 없을 수 있는데, `/rag` 강제
   명령(`routeForceRag`)에는 이미 있는 "RAG 결과 없음 → WEB_SEARCH 폴백"이 자동
   분류(`intentClassifier`)로 RAG가 선택되는 일반 경로(`route()`의 `case RAG`)에는
   빠져 있었다.

부수적으로, 챗 서비스 관리자 화면의 "최대 히스토리 턴수"(`max_history_turns`, ADR-0005
7단계 우선순위 체인으로 계산됨) 값이 `ChatController`에서 로그로만 찍히고 실제 히스토리
길이 제한(`extractHistory()`의 하드코딩된 `10`)에는 전혀 반영되지 않는 것도 함께 발견해
고쳤다.

## 결정 (What)

```
1. 검색 쿼리 재작성 (RagService, WidgetRagService)
   - history가 비어있으면 기존과 동일하게 원본 메시지를 그대로 임베딩.
   - history가 있으면 기존 chatClient로 LLM 1회 호출해 후속 질문을 독립형(standalone)
     질문으로 재작성한 뒤, 그 결과를 임베딩·검색에 사용.
   - LLM에 보내는 최종 프롬프트의 [현재 질문]에는 재작성 결과가 아닌 원본 사용자
     메시지를 그대로 사용 (검색 품질과 응답 충실도를 분리).
   - 재작성 LLM 호출 실패/빈 응답 시 원본 메시지로 폴백 (fail-open — 검색 품질 개선이
     검색 자체를 막아서는 안 됨).

2. 후속 질문 답변 깊이 확장 (RagService, WidgetRagService)
   - history가 있으면 topK를 2배(상한 20)로 늘려 답변 근거를 더 확보.
   - buildPrompt()에 "이전 답변을 반복하지 말고 참고자료 중 다루지 않은 부분까지 포함해
     더 구체적으로 답변하라"는 안내 블록을 history 있을 때만 추가. 전역 시스템 프롬프트
     (규칙 6: 간결하게 답변)는 첫 턴을 위해 그대로 두고, 후속 턴에서만 국소적으로 오버라이드.

3. RAG→WEB_SEARCH 자동 폴백 확대 (QueryRouterService)
   - routeForceRag()에 있던 폴백 로직을 ragWithWebFallback() 헬퍼로 추출해 route()의
     자동 분류 RAG 경로와 routeTextOnly()의 기본 분기에도 동일 적용.
   - rag.web-search.enabled=false면 폴백하지 않음 (관리자 설정 존중).
   - 트레이드오프: 폴백이 일어나도 메트릭은 최초 분류된 "RAG"로 집계된다
     (routeForceRag처럼 세밀한 재귀속은 하지 않음).

4. max_history_turns 배선 수정 (ChatController)
   - parameterResolver.resolve() 호출을 extractHistory()보다 먼저 실행하고, 그 결과의
     max_history_turns 값을 extractHistory()에 전달해 하드코딩된 10을 대체.
   - 값이 없거나 숫자가 아니면 기존과 동일하게 10으로 폴백 (기본 동작 보존).
```

`app-widget`과 `app-internal`은 ADR-0004에서 이미 "두 서비스만 존재하는 현재 규모에서는
조기 추상화를 피하고 서비스별로 로직을 유지한다"고 결정한 전례를 따라, 이번에도 `core`로
추출하지 않고 동일 패턴을 양쪽에 중복 구현했다. `max_history_turns` 배선 수정은
`app-internal` 전용이다 (`app-widget`은 이 항목에 대한 관리자 설정 자체가 없고
`WidgetChatController.MAX_HISTORY_TURNS = 6` 하드코딩만 있음).

## 결과 (Consequences)

### 장점
- "좀 더 자세히" 같은 맥락 의존 후속 질문의 검색 실패가 해소된다.
- 후속 질문 응답이 실제로 더 길고 구체적으로 나온다 (topK 확대 + 프롬프트 안내).
- RAG로 시작한 대화 중 내부 문서에 없는 용어를 되물어도 웹 검색으로 자연스럽게 이어진다.
- 관리자의 "최대 히스토리 턴수" 설정이 실제로 동작한다.

### 단점·트레이드오프
- 후속 턴마다 쿼리 재작성용 LLM 호출이 1회 추가되어 지연시간이 늘어난다 (첫 턴은 영향 없음).
- topK 확대로 후속 턴의 LLM 입력 토큰이 늘어난다 (상한 20으로 제한했으나 첫 턴보다 비용 증가).
- WEB_SEARCH 폴백 발생 시에도 메트릭은 원래 분류("RAG")로 집계되어, 폴백 발생 빈도를
  메트릭만으로는 구분할 수 없다.
- 재작성 쿼리는 대화별 고유해 캐싱 효과가 없다 (IntentClassifierService의 24h Redis
  캐시와 달리 캐시를 두지 않았다).

### 후속 작업
- WEB_SEARCH 폴백 발생을 별도 메트릭 라벨(예: `rag_fallback_web`)로 구분해 빈도 추적.
- 재작성 프롬프트 품질을 실제 대화 로그로 검증하고 필요 시 few-shot 예시 보강.

## 대안 (검토했으나 채택 안 한 옵션)

### 옵션 A — 히스토리 텍스트를 그대로 검색 쿼리에 이어붙이는 휴리스틱 (LLM 호출 없음)
최근 대화 일부를 원본 쿼리 앞에 붙여서 임베딩. 지연시간·비용이 없다는 장점이 있으나,
"그거", "좀 더" 같은 지시어 해소 정확도가 LLM 재작성보다 떨어진다.
**채택 안 한 이유**: 정확도를 우선했고, 실패 시 원본으로 폴백하는 fail-open 설계로
지연시간 리스크를 완화할 수 있다고 판단.

### 옵션 B — RagService/WidgetRagService 로직을 core로 추출
두 서비스의 중복을 제거할 수 있으나, ADR-0004에서 이미 동일한 트레이드오프를 검토해
"두 서비스만 존재하는 현재 규모에서는 조기 추상화를 피한다"고 결정한 바 있다.
**채택 안 한 이유**: 기존 결정과의 일관성 유지, 여전히 소비자가 2개뿐.

### 옵션 C — 모든 RAG 답변에 대해 topK를 상시 확대
후속 턴에서만이 아니라 항상 topK를 늘리면 구현이 더 단순해지지만, 첫 턴 답변까지
불필요하게 길어지고 토큰 비용이 상시 증가한다.
**채택 안 한 이유**: 문제가 후속 턴에 국한되어 있어 범위를 최소화.

## 참고

- ADR-0004 (챗/위젯 서비스 간 중복 허용 전례)
- ADR-0005 (`max_history_turns`를 포함한 7단계 파라미터 우선순위 체인)
