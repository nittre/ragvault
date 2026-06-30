# ADR-0005: qwen2.5vl:7b 단일 멀티모달 모델 통합

- **상태**: Accepted
- **결정일**: 2026-06-30
- **결정자**: 개발팀
- **관련 ADR**: ADR-0002
- **영향 받는 코드**: `app-internal/src/main/resources/application*.yml`, `app-widget/src/main/resources/application.yml`, `infra/compose.base.yml`, `infra/compose.internal.yml`, `infra/compose.widget.yml`

## 컨텍스트 (Why)

기존 구성:
- Chat(SQL/RAG/분류/종합): `qwen2.5:3b`
- VLM(이미지 분석): 별도 모델 또는 미설정
- 지식문서 이미지 캡셔닝: 미설정(캡셔닝 건너뜀)

문제:
1. **두 모델 동시 로드 시 메모리 비효율**: Ollama는 모델을 메모리에 상주시키므로 chat 모델(qwen2.5:3b)과 VLM 모델을 별도로 운영하면 두 모델이 동시에 RAM을 점유한다.
2. **전환 지연**: IMAGE 인텐트 처리 시 VLM 모델로 전환할 때 Ollama가 기존 모델을 언로드하고 새 모델을 로드하는 시간(수십 초)이 발생한다.
3. **이미지 캡셔닝 미활성화**: 지식문서 내 이미지가 벡터화되지 않아 이미지·표 기반 검색 불가.
4. **gemma3:4b SQL 환각**: 이전에 사용하던 gemma3:4b가 SQL 생성 시 존재하지 않는 컬럼을 생성하는 환각 문제가 확인됐다.

시스템 사양: RAM 24GB, Docker 할당 20.49GB — 7B 파라미터 모델 1개 + 임베딩 모델(bge-m3) 동시 로드에 충분.

## 결정 (What)

**chat·VLM·지식문서 이미지 캡셔닝 모두 `qwen2.5vl:7b` 단일 모델로 통합.**

```
챗 서비스(local):  spring.ai.ollama.chat.options.model = qwen2.5vl:7b
                   rag.chat.model                       = qwen2.5vl:7b
                   rag.vlm.model                        = qwen2.5vl:7b
                   rag.knowledge.vision-model           = qwen2.5vl:7b

위젯 서비스:       WIDGET_CHAT_MODEL                    = qwen2.5vl:7b
                   WIDGET_VISION_MODEL                  = qwen2.5vl:7b

Ollama:            OLLAMA_MAX_LOADED_MODELS             = 2
                   (qwen2.5vl:7b + bge-m3 동시 상주)
```

프로파일별 모델은 별도 관리:
- `local`: `qwen2.5vl:7b` (본 ADR)
- `dev`: `qwen3:8b` (dev 환경 별도 설정 유지)
- `prod`: `qwen2.5:14b-instruct-q4_K_M` + `qwen2.5-vl:7b-instruct-q4_K_M` (prod 별도 설정 유지)

## 결과 (Consequences)

### 장점
- **모델 1개 로드**: Ollama가 qwen2.5vl:7b 하나만 상주 → IMAGE 인텐트 전환 시 재로드 없음.
- **이미지 캡셔닝 활성화**: 지식문서 내 임베디드 이미지·표가 자동 캡셔닝돼 벡터 검색에 포함.
- **SQL 환각 해소**: gemma3:4b의 컬럼 환각 문제 제거.
- **운영 단순화**: 두 서비스가 동일 모델 사용 → Ollama 모델 관리 부담 감소.

### 단점·트레이드오프
- **모델 크기 증가**: 3B → 7B로 증가, 디스크 ~4.7GB 추가, 추론 속도 느려짐.
  - 24GB RAM 환경에서 허용 가능 수준으로 판단.
- **MVC 타임아웃 상향 필요**: VLM 추론이 느려 `spring.mvc.async.request-timeout`을 1200000ms(20분)으로 상향 (`application-local.yml`).
- **초기 다운로드**: 최초 배포 시 `ollama pull qwen2.5vl:7b` 필요 (~4.7GB).
  - `ollama-init` 컨테이너의 자동 pull 목록에 추가 권장.

### 후속 작업
- `compose.base.yml`의 `ollama-init` entrypoint에 `pull qwen2.5vl:7b` 추가 (현재 `qwen2.5:3b`만 pull)
- 추론 속도 측정 후 `num_ctx` 값 재검토

## 대안 (검토했으나 채택 안 한 옵션)

### 옵션 A — qwen2.5:3b(chat) + 별도 VLM 유지
- 두 모델 분리 운영. 메모리 점유가 분산되지만 전환 지연과 관리 복잡도 증가.
- **채택 안 한 이유**: 전환 시 재로드 지연(수십 초)이 사용자 경험을 저해.

### 옵션 B — qwen2.5vl:7b(chat) + 캡셔닝 비활성화 유지
- VLM 통합은 하되 지식문서 이미지 캡셔닝은 건너뜀.
- **채택 안 한 이유**: 이미지·표가 포함된 문서의 검색 정확도 개선 기회를 포기하는 것.

### 옵션 C — llava 또는 기타 전용 VLM 사용
- 비전 전용 모델은 이미지 분석 품질이 높지만 텍스트 생성 성능이 낮아 chat과 혼용 불가.
- **채택 안 한 이유**: 모델 분리로 인한 전환 지연 문제가 다시 발생.
