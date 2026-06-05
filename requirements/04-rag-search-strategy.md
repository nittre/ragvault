# RAG 검색 전략 상세 설계

> 벡터 검색 + 메타데이터 + LLM 호출로 이어지는 검색 파이프라인 설계.
> 동적 파라미터 관리로 운영 중 튜닝 가능.

관련 문서:
- [01-architecture.md](01-architecture.md) — 전체 아키텍처
- [03-data-sync-pipeline.md](03-data-sync-pipeline.md) — 데이터 동기화

---

## 목차

1. [개요 및 결정사항](#1-개요-및-결정사항)
2. [검색 흐름](#2-검색-흐름)
3. [거리 함수 및 유사도 임계값](#3-거리-함수-및-유사도-임계값)
4. [Top-K 검색](#4-top-k-검색)
5. [메타데이터 필터링](#5-메타데이터-필터링)
6. [컨텍스트 윈도우 관리](#6-컨텍스트-윈도우-관리)
7. [검색 결과 부족 시 fallback](#7-검색-결과-부족-시-fallback)
8. [임베딩 모델 버전 관리](#8-임베딩-모델-버전-관리)
9. [동적 설정 관리](#9-동적-설정-관리)
10. [성능 모니터링](#10-성능-모니터링)
11. [Phase별 도입 계획](#11-phase별-도입-계획)
12. [DB 스키마 (검색 관련)](#12-db-스키마-검색-관련)

---

## 1. 개요 및 결정사항

> **참고**: Phase 0부터 RAG와 함께 Text-to-SQL + 혼합 검색이 도입됨.
> 의도 분류기는 **6경로** 결정 (RAG / SQL / HYBRID / URL_FETCH / FILE / IMAGE).
> RAG / SQL / HYBRID 는 [08-text-to-sql.md](08-text-to-sql.md), URL_FETCH / FILE / IMAGE 는 [10-multimodal-files-url.md](10-multimodal-files-url.md) 참고.
> 이 문서는 RAG 경로의 검색 전략에 집중.

### 핵심 결정사항

| 항목 | Phase 0 결정 | 비고 |
|------|------------|------|
| 의도 분류 | LLM 기반 + 정적 규칙 — **6경로** (RAG / SQL / HYBRID / URL_FETCH / FILE / IMAGE) | Phase 0 도입. 08·10 문서 참고 |
| 거리 함수 | 코사인 `<=>` | 표준, bge-m3와 호환 |
| Top-K 기본값 | 5 | 균형 |
| 동적 K 조정 | 고정 (Phase 0) | Phase 1+ 동적 |
| 유사도 임계값 | 0.65 | 0.6~0.7 권장 범위 |
| 결과 부족 응답 | 솔직히 "모름" 응답 | Phase 1+ 웹 검색 fallback |
| 메타데이터 필터링 | LLM 자동 추론 | Phase 0 단순 시작 |
| 접근 그룹 필터(`access_groups`) | **Phase 0 모든 청크 `['all']`**, Phase 1+ user_groups 기반 활성 | 옵션 D — 03-data-sync-pipeline.md 섹션 3 참고 |
| 하이브리드 검색 (벡터+BM25) | Phase 1+ | 품질 평가 후 도입 |
| Re-ranking | Phase 1+ | Golden Dataset 검증 후 |
| 컨텍스트 윈도우 | K=5 고정 + 대화 10턴 | 단순 정책 |
| 임베딩 모델 변경 | 점진적 마이그레이션 | 두 버전 병존 기간 |
| 성능 모니터링 | 응답시간 + 사용자 피드백 | Phase 1+ RAGAS 추가 |

> **용어 주의**: "하이브리드 검색"이 두 개 있음:
> - **혼합 검색 (RAG + SQL)**: Phase 0 도입 — [08-text-to-sql.md](08-text-to-sql.md)
> - **하이브리드 검색 (벡터 + BM25)**: Phase 1+ — 이 문서 5절

### 동적 관리 결정사항

```
✅ search_config 테이블로 검색 파라미터 동적 변경
✅ Open WebUI에 3개 모델 변형 등록 (precise/balanced/broad)
⏳ Phase 1+ 요청별 미세 조정 (OpenAI API extra body)
```

---

## 2. 검색 흐름

```
[1] 사용자 질문 (Open WebUI)
    "A 상품 보증 기간이 얼마야?"
        ↓
[2] Open WebUI → Spring Boot OpenAI 호환 API
    POST /v1/chat/completions
    body: {
      "model": "company-rag-balanced",
      "messages": [...]
    }
        ↓
[3] Spring Boot가 model명으로 검색 변형 결정
    "balanced" → top_k=5, threshold=0.65
    (또는 search_config DB 기본값)
        ↓
[4] API Key 검증 + Rate Limit 체크 (Redis)
        ↓
[5] 시맨틱 캐시 확인 (Phase 1+)
    ├── 히트 → 즉시 응답
    └── 미스 → 다음 단계
        ↓
[6] 메타데이터 추론 (LLM 자동 분류)
    질문에서 source_type, 카테고리 등 추출
    "상품" 키워드 → source_type='product'
        ↓
[7] 질문 임베딩 (Ollama bge-m3)
    1024차원 벡터 생성
        ↓
[8] pgvector 검색 (Top-K + 임계값 + 접근 그룹)
    SELECT id, content, metadata, source_type
    FROM document_chunks
    WHERE (1 - (embedding <=> $1)) > 0.65
      AND source_type = $2          -- 메타데이터 필터
      AND access_groups && $3       -- 접근 그룹 교집합 (Phase 0: ARRAY['all'])
    ORDER BY embedding <=> $1
    LIMIT 5;

    -- $3 결정 규칙:
    --   Phase 0: 항상 ARRAY['all']  (모든 직원 동일 접근)
    --   Phase 1+: ['all'] + user_groups(user_email)  (사용자가 속한 그룹 합집합)
        ↓
[9] 결과 부족 검사
    ├── 0건 → "관련 정보 없음" 응답 반환
    ├── 1~2건 → LLM에 "정보 부족" 컨텍스트 전달
    └── 3~5건 → 정상 진행
        ↓
[10] 컨텍스트 조합
    시스템 프롬프트 + 검색 청크 + 대화 이력 + 질문
    토큰 예산 검사 (28K 초과 시 자르기)
        ↓
[11] Ollama LLM 호출 (qwen2.5:14b)
    SSE 스트리밍
        ↓
[12] **응답 후처리 PII 마스킹** (Layer 3, ADR-0008)
    PiiMasker.mask(response, STANDARD)
    rag_pii_masked_total{path="RAG"} 카운터
    → 모든 LLM 자연어화 응답에 일관 적용 (RAG/SQL/HYBRID/URL/FILE/IMAGE)
        ↓
[13] 응답 + 출처 메타데이터 반환
    Open WebUI가 토큰 단위 표시 (출처 카드: Top-K 전부, 점수 비노출, [N] 인라인 인용)
        ↓
[14] audit_log + 응답시간 기록
    (Phase 1+ 사용자 피드백 수집 시점)

> UX 결정 권위 출처: [docs/ux/user-journeys.md](../docs/ux/user-journeys.md) S2 (출처 카드·로딩 인디케이터·좋아요/싫어요 사유 폼)
```

---

## 3. 거리 함수 및 유사도 임계값

### 거리 함수: 코사인 `<=>`

```
[선택 이유]
- bge-m3는 L2 정규화된 벡터 출력
- 코사인 거리가 임베딩 검색 관습적 표준
- pgvector HNSW 인덱스에서 효율적

[수학적 동치]
정규화된 벡터에서는 코사인 ≡ 내적
→ 어느 쪽을 써도 결과 같음
→ 코사인 선택 (가독성)
```

### 유사도 임계값: 0.65

```
유사도 = 1 - 코사인 거리

[해석]
1.0  = 완벽히 동일
0.9  = 매우 유사 (거의 같은 문장)
0.8  = 유사 (같은 주제)
0.7  = 관련 있음
0.65 = 임계값 ← Phase 0 기본
0.5  = 약한 관련
0.0  = 무관

[적용]
SELECT *
FROM document_chunks
WHERE (1 - (embedding <=> $1)) > 0.65
  AND access_groups && $2          -- 접근 그룹 (Phase 0: ARRAY['all'])
ORDER BY embedding <=> $1
LIMIT 5;
```

### 거리 함수 변경 시

```
search_config.distance_function 값 변경:
- "cosine"        ← 기본 <=>
- "inner_product" ← <#> (약간 빠름)
- "l2"            ← <-> (비추천)

Spring Boot가 동적으로 쿼리 생성:
String op = config.getDistanceFunction(); // "cosine"
String sql = "ORDER BY embedding " + opMap.get(op) + " $1 LIMIT $2";
```

---

## 4. Top-K 검색

### 기본 정책

```
[기본값] K = 5
[허용 범위] 1~20
[모델 변형]
- precise:   K=3 (정밀, 빠름)
- balanced:  K=5 (균형) ← 기본
- broad:     K=10 (재현율 우선)
```

### Phase 0: 고정 K

```java
@Service
public class SearchService {
    
    public List<Chunk> search(String question, String modelVariant) {
        int topK = resolveTopK(modelVariant);  // 3, 5, 또는 10
        float[] questionVec = embeddingService.embed(question);
        
        return chunkRepository.findTopKSimilar(
            questionVec,
            topK,
            config.getSimilarityThreshold()
        );
    }
}
```

### Phase 1+ 동적 K 검토

```
질문 복잡도에 따라 K 자동 조정:
- 짧은 질문 (< 50 토큰): K=3
- 보통 질문 (50~200 토큰): K=5
- 긴 질문 / 복합 질문: K=10

판단 기준:
- 질문 토큰 수
- 키워드 수
- LLM 자동 분류
```

---

## 5. 메타데이터 필터링

### 5-0. 시스템 강제 필터 — `access_groups` (사용자 선택 불가)

```
[원칙]
모든 벡터 검색 쿼리는 access_groups 필터를 자동 추가한다.
이 필터는 사용자가 비활성화하거나 우회할 수 없다.

[Phase 0]
- 모든 청크의 access_groups = ['all']
- 모든 사용자에게 ARRAY['all'] 그룹 부여
- 결과: 같은 사내 직원은 동일 검색 결과
- 등록 시점에 admin이 민감 데이터 등록을 막아 보호한다 ([03-data-sync-pipeline.md 섹션 3](03-data-sync-pipeline.md) "등록 가드")

[Phase 1+]
- user_groups 테이블 도입 ([07-auth-security.md](07-auth-security.md))
- 검색 시 사용자가 속한 그룹 합집합을 필터로 사용
- rag_table_config / 청크별 allowed_groups · access_groups에 부서/팀 부여

[결정 근거]
옵션 D — Phase 0은 단순하게 운영하되 스키마는 미리 잡아두어
Phase 1+ 활성화 시 마이그레이션 비용을 최소화한다.
```

### Phase 0: LLM 자동 추론 (source_type 등 컨텍스트 필터)

```
[흐름]
1. 사용자 질문 분석
   "최근 상품 가격 알려줘"
        ↓
2. LLM에게 짧은 분류 요청 (qwen2.5 사용)
   "다음 질문이 어느 카테고리에 해당하나?
    [product, contract, customer, general]
    질문: '최근 상품 가격 알려줘'"
        ↓
3. LLM 응답: "product"
        ↓
4. 벡터 검색에 필터 적용
   WHERE source_type = 'product'
   AND (1 - (embedding <=> $1)) > 0.65
```

### 분류 LLM 호출 비용

```
[추가 비용]
- 작은 프롬프트 (200 토큰)
- 짧은 응답 (10 토큰)
- 약 50ms 추가

[최적화]
1. 같은 질문 캐싱 (Redis)
2. 명확한 키워드는 정규식으로 우회
   "상품" 단어 있음 → 바로 product 분류
```

### 분류 캐시

```
Redis 키: classify:{질문_해시}
값: "product"
TTL: 24시간

→ 같은 질문 반복 시 LLM 호출 생략
```

### Phase 1+: 사용자 명시 필터

```
Open WebUI에 카테고리 선택 UI 추가 (포크 필요)
또는 OpenAI API extra body로 전달:

{
  "messages": [...],
  "filters": {
    "source_type": "product",
    "metadata.category": "electronics"
  }
}
```

---

## 6. 컨텍스트 윈도우 관리

### qwen2.5:14b 토큰 예산

```
컨텍스트 윈도우: 32,768 토큰
실용 한계:      28,000 토큰 (안전 마진 15%)
```

### 파라미터 정의 (옵션 C — 단일 출처)

```
max_context_tokens
  = 검색된 청크의 총 토큰 한도 (이것만)
  ≠ 전체 프롬프트 길이 한도
  ≠ 대화 이력 한도

[다른 예산은 별도 관리]
- 시스템 프롬프트:  ~150 토큰 (05-prompt-design.md 섹션 9 기반, 코드 hardcoded)
- 대화 이력:        max_history_turns 파라미터로 제어 (turn 단위)
                    토큰 한도는 hardcoded ~5000
- 현재 질문:        ~500 토큰
- 응답 공간:        max_tokens 파라미터로 제어 (~2000 기본)

[채우기 순서]
1. 청크: Top-K 개를 채우다가 누적 토큰이 max_context_tokens 초과하면 중단
   → 사실상 자동 K 축소 (강제 종료)
2. 대화 이력: max_history_turns 안에서 최근부터 5000 토큰 한도까지 채움
3. 현재 질문 그대로 추가
4. 응답 공간은 LLM의 max_tokens 옵션으로 LLM이 알아서 사용
```

### 토큰 분배 (기본 정책)

```
시스템 프롬프트:    500 토큰
검색 청크 (K=5):    2,500 토큰  (500토큰 × 5)
대화 이력:          최대 5,000 토큰 (최근 10턴)
현재 질문:          최대 500 토큰
응답 공간:          2,000 토큰
─────────────────────────────────
실사용 총합:        약 10,000 토큰
여유:               약 18,000 토큰 (안전)
```

### 대화 이력 보존 정책

```
[정책] 최근 10턴 유지
- 1턴 = user 메시지 + assistant 응답
- 평균 500토큰 가정
- 10턴 = 약 5,000 토큰

[초과 시]
- 가장 오래된 턴부터 제거
- 시스템 메시지는 유지

[Phase 2+ 검토]
- 대화 요약 (오래된 대화를 요약본으로)
- LangChain ConversationSummaryMemory
```

### 토큰 초과 시 대응

```java
@Service
// 옵션 A — 청크의 token_count 는 동기화 시 document_chunks.token_count 컬럼에 미리 저장됨.
// Ollama /api/tokenize 결과이므로 정확. Context Builder 는 DB 값만 읽고 추가 Ollama 호출 0.
// 대화 이력·시스템 프롬프트·현재 질문은 런타임 카운트 (Redis 캐시) — 05-prompt-design.md 섹션 9 참고.
public class ContextManager {
    
    public String buildContext(Question q, List<Chunk> chunks, History history) {
        int budget = 28000 - SYSTEM_PROMPT_TOKENS;
        
        // 1. 청크 추가 (K가 동적이면 줄임)
        int chunkTokens = 0;
        List<Chunk> usedChunks = new ArrayList<>();
        for (Chunk c : chunks) {
            if (chunkTokens + c.tokens() > 5000) break;
            usedChunks.add(c);
            chunkTokens += c.tokens();
        }
        
        // 2. 대화 이력 추가 (최근부터)
        int historyTokens = 0;
        List<Message> usedHistory = new ArrayList<>();
        for (Message m : history.reversed()) {
            if (historyTokens + m.tokens() > 5000) break;
            usedHistory.add(0, m);  // 앞에 추가
            historyTokens += m.tokens();
        }
        
        return assemble(systemPrompt, usedChunks, usedHistory, q);
    }
}
```

---

## 7. 검색 결과 부족 시 fallback

### Phase 0: 솔직히 "모름" 응답

```
[케이스 1: 0건]
"관련된 정보를 찾을 수 없습니다.
질문을 다른 표현으로 시도하시거나 관리자에게 문의해주세요."

[케이스 2: 1~2건만 발견]
LLM 호출은 진행하되 프롬프트에 명시:
"검색된 자료가 제한적입니다. 확실한 부분만 답변하고
불확실한 부분은 솔직히 말하세요."

[케이스 3: 3~5건 정상]
일반 흐름 진행
```

### 시스템 프롬프트 정책

```
시스템 프롬프트에 명시:
"제공된 [참고 문서]에 없는 내용은 추측하지 말고
'해당 정보는 자료에 없습니다'라고 답하세요.
일반 지식으로 답변하지 마세요."

→ 환각(Hallucination) 방지
→ 신뢰도 ↑
```

### Phase 1+: 웹 검색 fallback

```
[흐름]
내부 검색 결과 < 3건 또는 평균 유사도 < 0.5
        ↓
사용자에게 확인 요청:
"내부 자료에 정보가 부족합니다. 웹 검색을 사용할까요?"
[예] [아니오]
        ↓
"예" 선택 시:
- Tavily API 또는 DuckDuckGo 호출
- 검색 결과를 컨텍스트로 추가
- LLM 응답 (출처: 웹 검색)

[주의]
- 데이터 보안 정책 검토 필요
- 고객사별 옵트인/아웃 옵션
- 검색 쿼리에 PII 포함 여부 검사
```

---

## 8. 임베딩 모델 버전 관리

### 점진적 마이그레이션 전략

```
[Phase 1: 새 모델로 신규 임베딩 시작]
- 현재: bge-m3
- 새 모델: bge-m3-new
        ↓
search_config.embedding_model = 'bge-m3-new'
        ↓
- 새 데이터 동기화는 v2로
- 기존 v1 데이터는 그대로

[Phase 2: 백그라운드 재임베딩]
- 관리자 트리거: /api/v1/admin/embeddings/migrate
- 배치 작업으로 v1 청크를 v2로 재임베딩
- 진행률 모니터링
        ↓
- 새 청크 생성 (embedding_model='bge-m3-new')
- 기존 v1 청크는 유지 (검색 시 병행 사용)

[Phase 3: 병존 기간 검색]
SELECT *
FROM document_chunks
WHERE embedding_model = current_model  -- 단일 모델
ORDER BY embedding <=> $1
LIMIT 5;

→ 현재 활성 모델 기준만 검색
→ 다른 버전은 무시
        ↓
[Phase 4: 마이그레이션 완료]
- 모든 v1 → v2 완료 확인
- v1 청크 일괄 삭제
- DB 정리
```

### 모델 변경 절차

```
1. 관리자: PATCH /api/v1/admin/search-config/embedding_model
   body: { "value": "bge-m3-new" }
        ↓
2. Spring Boot: search_config 캐시 갱신
        ↓
3. 신규 임베딩부터 v2 모델 사용
        ↓
4. 관리자: POST /api/v1/admin/embeddings/migrate
   body: { "from": "bge-m3", "to": "bge-m3-new" }
        ↓
5. 백그라운드 배치 시작 (sync_jobs 테이블 추적)
        ↓
6. 완료 후 관리자: DELETE /api/v1/admin/embeddings/old?model=bge-m3
```

---

## 9. 동적 설정 관리

### 핵심 원칙

```
검색 파라미터는 코드에 하드코딩하지 않음.
DB 테이블 search_config에 저장 → 운영 중 변경 가능.
관리자 API + Open WebUI 모델 변형 두 방식 제공.
```

### search_config 테이블

```sql
CREATE TABLE search_config (
    id              SERIAL PRIMARY KEY,
    config_key      VARCHAR(100) NOT NULL UNIQUE,
    config_value    JSONB NOT NULL,
    description     TEXT,
    valid_values    JSONB,            -- 검증용 (선택)
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- 초기 데이터
INSERT INTO search_config (config_key, config_value, description, valid_values) VALUES
('distance_function', '"cosine"', '거리 측정 방식',
  '["cosine","inner_product","l2"]'),
('top_k', '5', '검색할 청크 수',
  '{"min":1,"max":20}'),
('similarity_threshold', '0.65', '최소 유사도',
  '{"min":0.0,"max":1.0}'),
('max_context_tokens', '5000', '검색된 청크의 총 토큰 한도. 대화 이력/시스템 프롬프트/현재 질문/응답 공간은 별도 예산이다. broad(K=10×500=5000)까지 안전. Top-K × 평균 청크 토큰이 한도 초과 시 청크 채우다 자동 중단.',
  '{"min":1000,"max":20000}'),
('max_history_turns', '10', '대화 이력 최대 턴',
  '{"min":3,"max":50}'),
('embedding_model', '"bge-m3"', '활성 임베딩 모델',
  null),
('reranker_enabled', 'false', 'Re-ranker 사용 여부 (Phase 1+)',
  null),
('hybrid_search_enabled', 'false', '하이브리드 검색 (Phase 1+)',
  null),
('hybrid_vector_weight', '0.7', '하이브리드 검색 벡터 가중치',
  '{"min":0.0,"max":1.0}'),
('classification_cache_ttl', '86400', '분류 캐시 TTL(초)',
  null),
('webfallback_enabled', 'false', '웹 검색 fallback (Phase 1+)',
  null);
```

### 캐시 전략

```java
@Service
public class SearchConfigService {
    
    private final Map<String, JsonNode> cache = new ConcurrentHashMap<>();
    
    @Scheduled(fixedRate = 60000)  // 1분마다
    public void reloadCache() {
        configRepository.findAll().forEach(c -> 
            cache.put(c.getKey(), c.getValue())
        );
    }
    
    public int getTopK() {
        return cache.get("top_k").asInt(5);
    }
    
    public double getSimilarityThreshold() {
        return cache.get("similarity_threshold").asDouble(0.65);
    }
    
    public void invalidate() {
        reloadCache();
    }
}
```

### 관리자 API

```
GET    /api/v1/admin/search-config
       ← 전체 설정 조회
       response: [
         { "key": "top_k", "value": 5, "description": "..." },
         ...
       ]

GET    /api/v1/admin/search-config/{key}
       ← 특정 키 조회

PATCH  /api/v1/admin/search-config/{key}
       body: { "value": 7 }
       ← 즉시 반영 (캐시 무효화)
       검증: valid_values 범위 체크

POST   /api/v1/admin/search-config/reset
       body: { "key": "top_k" }  // 또는 null이면 전체
       ← 기본값으로 복원
```

### Open WebUI 모델 변형

```
3개 변형을 Open WebUI에 등록:

1. company-rag-precise
   - top_k=3, threshold=0.75
   - 정밀도 우선, 빠른 응답
   - "정확한 답만 원해요"

2. company-rag-balanced (기본)
   - top_k=5, threshold=0.65
   - 균형
   - 일반 사용

3. company-rag-broad
   - top_k=10, threshold=0.55
   - 재현율 우선
   - "광범위한 정보 원해요"

[Spring Boot 처리]
POST /v1/chat/completions
{ "model": "company-rag-precise", ... }
       ↓
ModelVariantResolver가 모델명으로 검색 파라미터 결정
       ↓
search_config의 기본값을 변형에 따라 override
```

### 모델 변형 우선순위

> 검색 파라미터의 최종 값은 [09-user-parameter-tuning.md 섹션 4 (통합 7단계 체인)](09-user-parameter-tuning.md#4-저장-정책--통합-7단계-우선순위-single-source-of-truth)이 권위 출처.
> 04 문서는 더 이상 별도 우선순위를 정의하지 않는다.

요약:
```
Stage 1 hardcoded
Stage 2 search_config (이 문서가 정의한 키들)
Stage 3 모델 변형 (precise/balanced/broad) ← 이 문서 9-2 참고
Stage 4 사용자 프로필
Stage 5 대화별 override
Stage 6 요청별 override (body의 rag_params)
Guard A 관리자 범위 클램핑
Guard B 관리자 강제 고정
```

→ `top_k`, `similarity_threshold` 등 04에서 다루는 파라미터도 모두 이 체인을 거쳐 최종값이 결정된다.
→ 모델 변형(`precise`/`balanced`/`broad`)은 Stage 3에서 적용되며, 사용자가 패널에서 저장한 값(Stage 4)에 의해 자연스럽게 override 된다.

### 관리자 화면 (구상)

```
[검색 설정 페이지]
┌─────────────────────────────────────────────┐
│  검색 파라미터                                │
│                                              │
│  거리 함수:       [코사인 ▼]                  │
│  Top-K:           [5    ] (1~20)             │
│  유사도 임계값:    [0.65 ] (0~1)              │
│  대화 이력 턴:    [10   ] (5~50)             │
│  컨텍스트 토큰:    [5000 ]                    │
│                                              │
│  임베딩 모델:     [bge-m3 ▼]    │
│   [변경 적용]                                 │
│                                              │
│  하이브리드 검색: [ ] 사용 (Phase 1+)         │
│   - 벡터 가중치:  [0.7]                       │
│  Re-ranker:       [ ] 사용 (Phase 1+)        │
│  웹 fallback:     [ ] 사용 (Phase 1+)        │
│                                              │
│  [저장] [기본값으로 복원]                      │
└─────────────────────────────────────────────┘

[모델 변형 — Open WebUI 노출]
┌─────────────────────────────────────────────┐
│  ✓ company-rag-precise   (K=3, T=0.75)      │
│  ✓ company-rag-balanced  (K=5, T=0.65) ★기본 │
│  ✓ company-rag-broad     (K=10, T=0.55)     │
│   [Add Variant] [Edit] [Disable]             │
└─────────────────────────────────────────────┘
```

---

## 10. 성능 모니터링

### Phase 0 모니터링 지표

```
[기술 지표 — Prometheus + Grafana]
- search_latency_seconds (벡터 검색 시간)
  - p50, p95, p99
- llm_latency_seconds (Ollama 응답 시간)
- total_response_time_seconds
- search_results_count (반환 청크 수)
- search_threshold_filtered_count (임계값 미달로 제외된 수)
- search_zero_results_count (0건 응답)

[CloudWatch — 인프라]
- GPU 사용률
- pgvector 쿼리 시간
- 동시 요청 수

[비즈니스 지표 — DB]
- 일일 검색 횟수
- 가장 많은 검색 키워드
- 응답 부족 (0건) 빈도
- 사용자 피드백 (👍/👎)
```

### 사용자 피드백 수집

```
Open WebUI 기본 제공:
- 메시지 옆 👍 / 👎 버튼
- 클릭 시 Open WebUI DB에 저장
        ↓
주기적으로 Spring Boot가 동기화:
- Open WebUI DB에서 피드백 조회
- audit_logs에 합치기
- Phase 1+ Golden Dataset 구축 자료
```

### Phase 1+ Golden Dataset

```sql
CREATE TABLE evaluation_set (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    question        TEXT NOT NULL,
    expected_answer TEXT,
    expected_sources JSONB,
    category        VARCHAR(50),
    difficulty      VARCHAR(20),
    created_by      VARCHAR(200),
    created_at      TIMESTAMP DEFAULT NOW()
);

-- 운영 데이터에서 좋은 질문 + 좋은 답변을 골라 등록
-- Phase 1+ RAGAS 자동 평가 입력값
```

### RAGAS 평가 지표 (Phase 1+)

```
Faithfulness (충실도):
- 답변이 검색 청크에 충실한가
- 환각 없는지 측정

Answer Relevancy:
- 답변이 질문에 적절한가

Context Precision:
- 검색된 청크가 진짜 관련 있는가

Context Recall:
- 필요한 청크를 다 찾았는가

[자동 평가]
매일 새벽 4시 Golden Dataset으로 평가
점수 추이를 Grafana에 표시
회귀 발생 시 Discord 알람
```

---

## 11. Phase별 도입 계획

### Phase 0 — MVP

```
☑ 코사인 거리 함수
☑ Top-K=5 고정
☑ 유사도 임계값 0.65
☑ 메타데이터 자동 추론 (LLM)
☑ 컨텍스트 윈도우 단순 정책 (K=5 + 대화 10턴)
☑ 검색 부족 시 "모름" 응답
☑ 임베딩 모델 버전 관리 (스키마 준비)
☑ search_config 동적 설정
☑ Open WebUI 3개 모델 변형
☑ 기본 모니터링 (응답시간 + 피드백)
```

### Phase 1 — 정식 출시

```
☑ 시맨틱 캐싱 (Redis)
☑ 하이브리드 검색 (벡터 + BM25)
☑ Re-ranking (BGE-Reranker)
☑ 웹 검색 fallback (Tavily/DuckDuckGo)
☑ RAGAS 자동 평가 시스템
☑ Golden Dataset 구축
☑ 동적 K 조정 (질문 복잡도 기반)
☑ **HyDE (Hypothetical Document Embeddings) 검토** — RAGAS context_recall < 0.8 인 경우 도입. LLM 호출 +1 (응답시간 +500ms~1.5s) trade-off.
☑ **ReAct / Tool-use 검토** — 도구가 6경로 이상으로 늘어나거나 복합 질문(예: "URL 분석 + DB 검색") 빈도 ↑ 시점에 도입. qwen2.5-instruct + Spring AI `@Tool` 활용 가능.
☑ 사용자 명시 필터 (Open WebUI 포크)
```

### Phase 2 — 확장

```
☑ 대화 요약 (긴 대화 압축)
☑ 한국어 NER 기반 PII 마스킹
☑ 임베딩 모델 자동 업그레이드 파이프라인
☑ 멀티 임베딩 (도메인별 다른 모델)
☑ 출처 신뢰도 점수
☑ 응답 캐시 (자주 묻는 질문)
```

---

## 12. DB 스키마 (검색 관련)

### 기존 테이블 사용

```sql
-- 03-data-sync-pipeline.md에서 정의됨:
- document_chunks (벡터 저장)
- audit_logs (검색 이력)
```

### 신규 테이블

```sql
-- 동적 설정
CREATE TABLE search_config (
    id              SERIAL PRIMARY KEY,
    config_key      VARCHAR(100) NOT NULL UNIQUE,
    config_value    JSONB NOT NULL,
    description     TEXT,
    valid_values    JSONB,
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- 모델 변형
CREATE TABLE model_variants (
    id                  SERIAL PRIMARY KEY,
    variant_name        VARCHAR(100) NOT NULL UNIQUE,  -- 'company-rag-precise'
    display_name        VARCHAR(200),                   -- '정밀 검색'
    description         TEXT,
    top_k               INT NOT NULL,
    similarity_threshold DECIMAL(3,2) NOT NULL,
    is_default          BOOLEAN DEFAULT false,
    is_active           BOOLEAN DEFAULT true,
    created_at          TIMESTAMP DEFAULT NOW()
);

-- 초기 데이터
INSERT INTO model_variants (variant_name, display_name, top_k, similarity_threshold, is_default) VALUES
('company-rag-precise',  '정밀 검색',     3, 0.75, false),
('company-rag-balanced', '균형 검색',     5, 0.65, true),
('company-rag-broad',    '광범위 검색',  10, 0.55, false);

-- Phase 1+ 평가 데이터셋
CREATE TABLE evaluation_set (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    question        TEXT NOT NULL,
    expected_answer TEXT,
    expected_sources JSONB,
    category        VARCHAR(50),
    difficulty      VARCHAR(20),
    created_by      VARCHAR(200),
    created_at      TIMESTAMP DEFAULT NOW()
);

-- Phase 1+ 평가 실행 결과
CREATE TABLE evaluation_runs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    started_at          TIMESTAMP DEFAULT NOW(),
    completed_at        TIMESTAMP,
    faithfulness        DECIMAL(4,3),
    answer_relevancy    DECIMAL(4,3),
    context_precision   DECIMAL(4,3),
    context_recall      DECIMAL(4,3),
    config_snapshot     JSONB                -- 실행 시점의 search_config
);
```

---

## 다음 단계

이 검색 전략 기반으로:
- **E. 프롬프트 설계** (다음 진행) — 시스템 프롬프트, 출처 인용, 환각 방지
- **F. 에러 처리/장애 대응**
- **C. 인증/인가**
