# ADR-0004: LLM 추상화 — Spring AI 전면 채택 + Q4_K_M 양자화 표준

- **상태**: Accepted
- **결정일**: 2026-05-19
- **결정자**: 시니어 백엔드 엔지니어 (사용자 옵션 A 채택)
- **관련 ADR**: —
- **영향 받는 문서**: `requirements/02-stack-reference.md` Spring AI · qwen2.5, `requirements/01-architecture.md` 환경 매트릭스, 모든 LLM 호출 코드 예시 (03·04·05·08)

## 컨텍스트

### 문제 ① — LLM 클라이언트 추상화 불일치
선언적으로는 Spring AI 채택(`02-stack-reference.md`, `01-architecture.md 14-5`)이라 적혀 있었으나, 실제 코드 예시는 모두 raw `OllamaClient` 직접 호출이었다 — `embeddingService.embed()`, `ollamaClient.embed()`, `ollama.generate()` 등. 자체 wrapper 인지 Spring AI 인지 모호.

### 문제 ② — GPU 양자화 정책 부재
`qwen2.5:14b ~9GB` 만 적혀 있고 어떤 양자화 기준인지 명시 없음. g5.xlarge VRAM 24GB 에 FP16(~28GB) 안 들어감 / Q8(~17GB) 빡빡 / Q4(~11GB) 여유 — 결정 안 됨.

## 결정

### A. Spring AI 전면 채택
- LLM Chat: `ChatClient` + `OllamaChatModel`
- Embedding: `OllamaEmbeddingModel`
- 모든 LLM 호출(의도 분류·SQL 생성·자연어화·종합)에 동일 `ChatClient` 사용
- pgvector 는 raw SQL 유지 (`access_groups && $userGroups` 동적 필터 때문)
- **금지**: 자체 `OllamaClient` wrapper 작성

### B. Q4_K_M 양자화 표준
- 모든 환경(로컬·dev·상용) 통일
- 모델별:
  - 로컬·dev: `qwen2.5:7b-instruct-q4_K_M` (~6GB VRAM/RAM)
  - 상용: `qwen2.5:14b-instruct-q4_K_M` (~11GB VRAM)
  - 멀티모달: `qwen2.5-vl:7b-instruct-q4_K_M` (~6GB VRAM, 듀얼 모델로 추가)
- 임베딩: `bge-m3` (모든 환경 동일, 양자화 무관)
- 격상 트리거: Phase 1+ RAGAS faithfulness < 0.85 → Q5_K_M 검토

### C. 환경별 application.yml 통일
```yaml
spring.ai.ollama:
  base-url: <env-specific>
  chat.options.model: <env-specific>   # qwen2.5:7b 또는 14b
  embedding.options.model: bge-m3
rag.vlm.model: qwen2.5-vl:7b-instruct-q4_K_M
```

## 결과

### 장점
- Spring AI advisor 패턴으로 Phase 1+ Resilience4j / RAGAS / 시맨틱 캐싱 통합 가능
- LLM 공급자 교체 자유 (Ollama → vLLM, emergency 시 Bedrock 등)
- Metrics(`spring.ai.*`) 자동 노출 → Prometheus
- Q4_K_M 은 GGUF·Ollama 산업 표준 → AMI 빌드 단순 (`ollama pull qwen2.5:14b-instruct-q4_K_M`)
- g5.xlarge 인스턴스 그대로 사용 (격상 불필요)

### 단점·트레이드오프
- Spring AI 1.0 이 비교적 새로움 (2024 GA) → 미세 버그 가능
- Q4 는 FP16 대비 약간의 품질 손실 (RAG 작업에 체감 영향 작음)
- 환경별 모델 크기 차이로 dev/local 7B vs 상용 14B — 프롬프트 튜닝·RAGAS 평가는 상용 14B 인프라에서 해야 정확

### 후속 작업
- AMI 빌드 절차: `ollama pull qwen2.5:14b-instruct-q4_K_M bge-m3 qwen2.5-vl:7b-instruct-q4_K_M` 사전 포함
- 코드 리뷰 회귀 검증: raw `OllamaClient` 자체 wrapper 작성 시 BLOCKER
- 04·08·03·05 문서의 코드 예시는 실제 구현 시점에 Spring AI 패턴으로 정렬

## 대안

### 옵션 B — raw OllamaClient 자체 wrapper
단순하지만 Spring AI 가 제공하는 RAG advisor / ChatMemory / 표준 metrics 부재. 사실상 Spring AI 재발명.

### 옵션 C — 하이브리드 (일부 Spring AI, 일부 raw)
가장 흔한 안티패턴. 시간 지나면서 두 패턴이 뒤섞임. 거부.

### 양자화 대안
- FP16: g5.xlarge 안 들어감, g5.2xlarge 필요 → 비용 +$220/월. Phase 0 부적합.
- Q8_0: VRAM 17/24GB 빡빡. Open WebUI · 시스템 메모리 고려 시 OOM 위험.
- Q5_K_M: 12GB VRAM, 97% 품질. Phase 0 보수적이라면 검토 가능하나 Q4 vs Q5 체감 차이 작음.

## 참고

- 권위 출처: `requirements/02-stack-reference.md` Spring AI / qwen2.5 섹션
- 환경 매트릭스: `requirements/01-architecture.md` 섹션 2-1·4-4
