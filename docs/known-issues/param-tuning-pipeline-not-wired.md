# 채팅 파라미터 튜닝(ADR-0005)이 실제 파이프라인에 배선되지 않음

**상태**: 미해결 — 후속 작업 필요
**연관 플랜**: `.claude/plans/iridescent-baking-pine.md` ("챗 어드민 파라미터 한도 vs 채팅 화면 파라미터 설정 불일치" 원인 분석, A/B 섹션)
**이 문서가 만들어진 배경**: 위 플랜에서 "어드민 파라미터 한도와 채팅 화면 설정이 다르게 보인다"는 문제를 조사하다가, UI가 어드민 한도를 조회하지 않는 문제(이미 수정 완료)와는 별개로 훨씬 근본적인 문제를 발견했다. 이 문서는 그중 **이번 작업 범위에서 제외하고 별도로 분리한 부분**만 다룬다.

## 이번 작업에서 이미 고친 것 (참고용, 이 문서의 대상 아님)

- `ParamSidePanel.tsx`가 `GET /api/v1/user/param-profile`을 조회하지 않아 슬라이더 min/max·잠금값이 하드코딩되어 있던 문제 → 수정 완료
- `ParamLimitInfo`에 `fixedValue`가 없어 잠금 파라미터 실제 값을 표시할 수 없던 문제 → 수정 완료
- `max_history_turns` 음수 입력 시 크래시 → 클램프 추가로 수정 완료
- **프론트(camelCase: `topK`, `threshold`, `topP`, `maxTokens`, `queryTimeoutSec`, `maxResultRows`, `forcePath`, `hybridStyle`, `maxHistoryTurns`) ↔ 백엔드(snake_case: `top_k`, `similarity_threshold`, `top_p`, `max_tokens`, `query_timeout_sec`, `max_result_rows`, `force_path`, `hybrid_synthesis_style`, `max_history_turns`) 키 이름 불일치** → `frontend/internal/src/utils/ragParamKeys.ts` 매핑을 추가해 수정 완료. 이 버그 때문에 `temperature`(우연히 이름이 같음)를 제외한 모든 파라미터가 `rag_params`로 보내도 서버에서 전혀 인식되지 않고 있었다.
- 키 매핑 수정 덕분에 `max_history_turns`는 이제 요청 단위로 실제로 적용된다(`ChatController.resolveMaxHistoryMessages()`가 이 값을 실제로 소비하기 때문).

## 이 문서가 다루는 미해결 문제

키 매핑을 고쳐도, 아래 9개 파라미터는 **애초에 실제 RAG/SQL/LLM 실행 경로 어디에서도 소비되지 않기 때문에** 사용자가 어떤 값을 선택하든(그리고 어드민이 어떤 한도를 걸든) 채팅 동작에 영향이 없다.

| 파라미터 | 실제 소비 여부 | 근거 |
|---|---|---|
| `top_k` | ❌ | `RagService.java`가 `SearchConfigMappingService`/`EffectiveParams`를 전혀 참조하지 않고, "후속 질문이면 topK를 늘린다"는 내부 하드코딩 로직만 사용 (`RagService.java:91-92` 주석) |
| `similarity_threshold` | ❌ | `RagService.java`가 `@Value("${rag.search.default-threshold:0.65}")` application.yml 정적값만 사용 |
| `temperature` | ❌ | 전체 `app-internal`/`core`에서 `OllamaOptions`/`.temperature(`을 검색한 결과 `AiConfig.java:42-49`의 **VLM 전용 ChatClient에 0.3 고정값**을 설정하는 것이 유일한 사례. 메인 `chatClient`(RAG/SQL/HYBRID 등 대부분 경로)는 요청 단위 temperature를 전혀 설정하지 않음 |
| `top_p` | ❌ | 코드베이스 전체에서 `ParameterValidator`/`HardcodedDefaults`/마이그레이션 SQL 외에는 참조하는 곳이 없음 — 완전 미사용 |
| `max_tokens` | ❌ | `EffectiveParams.values()`에서 이 키를 꺼내 쓰는 소비처를 찾지 못함 |
| `query_timeout_sec` | ❌ | 실제 SQL 타임아웃은 `core/SqlExecutorService.java:33` `QUERY_TIMEOUT_SEC = 10` **하드코딩 상수**가 독립적으로 강제(`ps.setQueryTimeout()`, `:71-72,119`). 값이 Stage1 기본값(10)과 같은 건 우연 |
| `max_result_rows` | ❌ | 실제 SQL 행수 제한은 `core/SqlExecutorService.java:34` `MAX_ROWS = 1000` **하드코딩 상수**가 독립적으로 강제(`ps.setMaxRows()`, `LIMIT` 자동 추가 `:133-137`). `TextToSqlService.java`에도 별도 `SYNTHESIS_ROW_LIMIT = 1000` 하드코딩 상수 존재 |
| `force_path` | ❌ | UI 드롭다운 값은 `rag_params.force_path`로 전송되지만, 실제 라우팅 강제는 `QueryRouterService.route()`의 `routingHint` 인자로만 동작(`QueryRouterService.java:82,109-118`). `routingHint`는 `ChatPage.tsx:78-89`에서 메시지가 `/rag `, `/web `, `/sql `로 시작하는지 **슬래시 커맨드 파싱으로만** 결정되며 `rag_params.force_path`는 전혀 참조하지 않음. **드롭다운은 100% 장식용** |
| `hybrid_synthesis_style` | ❌ | `EffectiveParams.values()`에서 이 키를 꺼내 쓰는 소비처를 찾지 못함. `HybridQueryService.query()`가 독자적으로 처리 |
| `max_history_turns` | ✅ (이번에 키 매핑 수정으로 정상화됨) | `ChatController.resolveMaxHistoryMessages()`가 실제로 이 값을 읽어 히스토리 메시지 수를 제한 |

`sql_temperature`/`sql_few_shot_examples`/`max_context_tokens`(Guard B 고정 3종)가 `TextToSqlService`의 실제 SQL 생성 LLM 호출에 쓰이는지는 미확인 — 추가 조사 필요.

## 왜 별도 이슈인가

- 이걸 고치려면 `RagService`, `AiConfig`, `TextToSqlService`, `core/SqlExecutorService`, `QueryRouterService`, `HybridQueryService` 등 여러 서비스의 실행 로직을 바꿔야 한다.
- 예를 들어 temperature/top_k가 실제로 반영되기 시작하면, 지금까지 고정 동작하던 RAG 검색 결과와 LLM 응답 품질이 사용자 설정에 따라 달라지기 시작한다 — 되돌리기 어려운 동작 변화이므로, 어느 파라미터부터·어떤 우선순위로 배선할지 별도 의사결정이 필요하다.
- `ParameterResolver.java`의 Stage 3 주석("모델 변형 — Phase 1+ 구현 예정")을 보면, 애초에 단계적으로 미완성 상태를 의도했을 가능성도 있다. 착수 전 제품 오너와 "지금 이 기능이 실제로 필요한지, 어느 범위부터 볼지" 확인 필요.

## 함께 처리해야 할 서버 측 검증 공백 (B 나머지)

파라미터를 실제로 배선하는 순간, 아래 검증 공백도 함께 살아나므로 **같은 작업에 묶어서 처리**해야 한다.

- `V6__m6_admin_schema.sql:38-43` 시드 데이터 확인 결과 `admin_param_limits`에는 `top_k`, `similarity_threshold`, `temperature`, `max_tokens` 4개만 기본 row가 있다. `top_p`, `query_timeout_sec`, `max_result_rows`, `max_history_turns`는 기본 row가 없다.
- `ParameterResolver.applyGuardA()`(`ParameterResolver.java:191-222`)는 `admin_param_limits`에 row가 있는 키만 클램핑한다. row가 없는 키는 어떤 제한도 받지 않는다.
- `ParameterValidator`(`ParameterValidator.java:36-50`)는 이 4개 키에 대해 하드코딩 폴백 범위를 갖고 있지만, `ChatController`는 이 검증기를 전혀 호출하지 않는다(`ParameterValidator`는 `UserParamController`/`ConversationParamController`의 프로필·대화별 override 저장 시에만 사용됨).
- **권장**: `ParameterResolver.applyGuardA()`가 row 유무와 무관하게 `ParameterValidator`와 동일한 하드코딩 폴백 범위를 최소 안전망으로 적용하도록 통합하거나, `ChatController`에서도 `ParameterValidator.validate()`를 호출해 Stage 6 진입 전에 거부(reject)하는 방안을 검토.

## 제안하는 다음 단계

1. 제품 오너와 "지금 이 파라미터들을 실제로 배선할 필요가 있는지"부터 확인 (특히 temperature/top_k처럼 응답 품질에 직접 영향을 주는 파라미터는 신중한 롤아웃 필요)
2. 배선하기로 하면, 파라미터별로 우선순위를 정해 하나씩 진행 (예: `force_path`는 UI-로직 연결만 하면 되는 저위험 항목이라 먼저 시작하기 좋음)
3. 각 파라미터를 배선하는 동시에 위 "B 나머지" 검증 공백을 함께 메꿀 것
