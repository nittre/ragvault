# 기술 스택 레퍼런스

> RAG 시스템에 사용되는 모든 기술 스택의 상세 설명.
> 주니어 개발자도 이해할 수 있도록 작성.

관련 문서: [01-architecture.md](01-architecture.md)

---

## 목차

1. [클라이언트 / 프론트엔드](#1-클라이언트--프론트엔드)
2. [LLM / AI 스택](#2-llm--ai-스택)
3. [데이터베이스 스택](#3-데이터베이스-스택)
4. [애플리케이션 스택](#4-애플리케이션-스택)
5. [메시지 큐 / 캐시](#5-메시지-큐--캐시)
6. [컨테이너 / 배포](#6-컨테이너--배포)
7. [AWS 인프라 스택](#7-aws-인프라-스택)
8. [보안 / 네트워크](#8-보안--네트워크)
9. [IaC / CI/CD / 배포 도구](#9-iac--cicd--배포-도구)
10. [모니터링 / 알람](#10-모니터링--알람)

---

## 1. 클라이언트 / 프론트엔드

### Open WebUI ★ Phase 0 사용
**한 줄 설명**: ChatGPT 클론 오픈소스 챗 UI. Ollama용 자체 호스팅 솔루션.

**왜 이걸 쓰냐**:
- 완성품 (별도 프론트엔드 개발 불필요)
- Ollama 네이티브 통합
- OpenAI 호환 API 지원 → Spring Boot 백엔드 연결
- 멀티 사용자 + 인증 내장
- 파일 업로드, 대화 히스토리, 코드 하이라이팅 기본 제공
- Docker 한 줄로 배포
- 사내 서비스 모델과 궁합 좋음 (고객사 1개 = WebUI 1개)

**Spring Boot 통합**:
```
Open WebUI 설정 (Settings > Connections):
- OpenAI API URL: http://spring-boot:8080/v1
- API Key: (Spring Boot가 검증할 키)
- Default Model: company-rag-model
```

**관리자 설정**:
- 우리가 초기 admin 계정 생성
- 고객사 관리자에게 인계
- 고객사가 자체적으로 사용자 추가/관리

**업데이트 정책**:
- 고객사별 별도 일정 (일괄 업데이트 안 함)
- 사전 공지 후 점검 시간에 진행

---

### 아래 항목들은 Phase 2+ 검토용 (현재 Open WebUI로 대체)

> Phase 2에서 CLI Tool 개발 또는 자체 Web UI가 필요한 경우 참고.

---

### Next.js — Phase 2 검토용
**한 줄 설명**: React 기반 풀스택 웹 프레임워크. Vercel이 만들고 유지보수.

**왜 이걸 쓰냐**:
- React 생태계 (가장 큰 커뮤니티)
- SSR/SSG로 빠른 초기 로딩
- App Router로 모던한 라우팅
- API Route로 백엔드 프록시 (Spring Boot 호출 시 CORS 우회)
- Vercel AI SDK 네이티브 통합

**호스팅 (상용)**: 빌드 결과물을 S3 + CloudFront에 배포.

---

### Vercel AI SDK
**한 줄 설명**: AI 챗봇 만들 때 SSE 스트리밍 처리를 자동화해주는 공식 라이브러리.

```typescript
'use client';
import { useChat } from 'ai/react';

export default function Chat() {
  const { messages, input, handleInputChange, handleSubmit } = useChat({
    api: '/api/chat',
  });
  return ( /* ... */ );
}
```

**자동 처리**: SSE 스트리밍 파싱, 메시지 상태, 로딩, 토큰 단위 UI 업데이트, 자동 스크롤.

---

### assistant-ui
**한 줄 설명**: ChatGPT/Claude 스타일 챗 UI 드롭인 컴포넌트.

```typescript
import { Thread } from "@assistant-ui/react";
export default function ChatPage() {
  return <Thread />;  // ChatGPT UI 완성
}
```

**기반**: shadcn/ui + Vercel AI SDK 위에 구축.

---

### shadcn/ui
**한 줄 설명**: Tailwind CSS 기반 React 컴포넌트 모음 (복사형).

**특징**: 디자인 일관성, 커스터마이징 자유, 다크 모드 기본 지원.

---

### SSE (Server-Sent Events)
**한 줄 설명**: 서버 → 클라이언트 단방향 실시간 스트리밍 HTTP 표준.

**vs WebSocket**: SSE는 단방향(서버→클라)이고 HTTP 기반이라 단순. LLM 챗봇 표준.

---

### Node.js (CLI 런타임) — Phase 2 검토용
**한 줄 설명**: V8 엔진 기반 자바스크립트 런타임. CLI 도구 표준.

---

### CLI 라이브러리

| 라이브러리 | 역할 |
|-----------|------|
| **commander** | CLI 명령어/옵션 파싱 |
| **inquirer** | 대화형 프롬프트 |
| **chalk** | 터미널 컬러 출력 |
| **ora** | 로딩 스피너 |
| **eventsource-parser** | SSE 응답 파싱 |
| **conf** | 설정 파일 (`~/.companyrag/config.json`) |
| **undici** | HTTP 클라이언트 |

---

## 2. LLM / AI 스택

### Ollama
**한 줄 설명**: 컴퓨터에서 LLM을 직접 돌릴 수 있게 해주는 도구.

```
GPT, Claude는 외부 API 써야 함 → 비용 들고, 데이터 외부 전송
Ollama: 모델 파일 다운받아서 직접 실행 → 무료, 데이터 안 나감
```

**왜 쓰냐**:
- 무료 (모델 사용료 없음)
- 데이터 외부 전송 없음 (회사 데이터 보안)
- 로컬-개발-상용 모두 같은 엔진 사용 (Dev/Prod Parity)

**사용 환경**: 로컬 / 개발 서버 / AWS EC2 (상용)

---

### bge-m3
**한 줄 설명**: BAAI(베이징 AI 연구소)가 만든 다국어 특화 임베딩 모델. M3 = Multi-Functionality · Multi-Linguality · Multi-Granularity.

```
"안녕하세요" → [0.08, -0.31, 0.92, ...] (1024차원)
```

**왜 필요한가**: 컴퓨터는 텍스트 의미를 직접 못 비교함. 숫자로 바꿔서 "벡터 거리"를 계산해야 의미 유사도를 알 수 있음.

**스펙**:
- 차원: 1024 (dense 기본값)
- 한국어 지원: **우수** (100+ 언어 다국어 특화, nomic-embed-text 대비 한국어 성능 향상)
- 모델 크기: ~570MB
- 추론 속도: 빠름 (GPU 없이도 가능)

**라이선스**: **Apache 2.0** ✅ 상업 사용 자유 (모델 가중치·코드). 출처: https://huggingface.co/BAAI/bge-m3

---

### qwen2.5
**한 줄 설명**: Alibaba가 만든 한국어 잘하는 오픈소스 LLM.

**양자화 표준 (모든 환경 통일): Q4_K_M**
- GGUF 표준, Ollama 기본값
- 4비트 K-quants Medium — 메모리·속도·품질 균형
- 환경 간 응답 패턴 일치 (Dev/Prod Parity)
- Phase 1+ RAGAS faithfulness < 0.85 시 Q5_K_M 격상 검토

**버전별 사용 (Q4_K_M 기준)**:
| 버전 | 모델 파일 | 추론 VRAM | 환경 | 비고 |
|------|----------|-----------|------|------|
| qwen2.5:7b-instruct-q4_K_M | ~4.7GB | ~6GB | 로컬 (맥북 M3 16GB, Metal) + 개발 서버 (CPU) | hot reload 우선 |
| qwen2.5:14b-instruct-q4_K_M | ~9GB | ~11GB | 상용 (EC2 g5.xlarge, A10G 24GB) | 품질 우선 |
| qwen2.5-vl:7b-instruct-q4_K_M | ~5GB | ~6GB | 모든 환경 (멀티모달 IMAGE 경로) | 채팅 이미지 처리. 10-multimodal-files-url.md 섹션 5 참고 |
| qwen2.5:32b-instruct-q4_K_M | ~19GB | ~22GB | (검토용) EC2 g5.2xlarge 이상 | Phase 2+ |

**왜 이걸 쓰냐**:
- 한국어 성능이 Llama보다 좋음
- 비즈니스 데이터에 적합
- 오픈소스 (상업 사용 가능)

**라이선스 (Phase 0 기준)**:
- Qwen2.5-7B  : **Apache 2.0** ✅ 상업 사용 자유
- Qwen2.5-14B : **Apache 2.0** ✅ 상업 사용 자유
- Qwen2.5-32B : Apache 2.0 (Phase 2+ 검토용)
- Qwen2.5-72B : **Qwen License** (Tongyi Qianwen) — MAU 1억 이상이면 별도 라이선스. **Phase 2+ 검토 시 재확인 필요**.
- 출처: https://huggingface.co/Qwen

**다른 양자화 옵션 (참고)**:
| 양자화 | 14b 추론 VRAM | 품질 | 채택? |
|--------|---------------|------|-------|
| FP16 (풀) | ~30GB | 100% | ❌ g5.xlarge 안 들어감 (g5.2xlarge 필요, +$220/월) |
| Q8_0 | ~17GB | 99% | ❌ 빡빡함 (Open WebUI, 시스템 메모리 고려 시 OOM 위험) |
| **Q5_K_M** | ~12GB | 97% | △ Phase 1+ 품질 부족 시 격상 |
| **Q4_K_M** ★ | ~11GB | 95% | ✅ Phase 0 표준 |

---

### EC2 + Ollama (상용 LLM 서버)
**한 줄 설명**: GPU 달린 EC2 인스턴스에 Ollama 직접 설치해서 LLM 운영.

```
Bedrock: AWS API 사용 → 사용량 기반 과금, 모델 튜닝 제한
EC2 + Ollama: 직접 운영 → 월 고정 비용, 완전 자유
```

**왜 이걸 쓰냐**:
- 모델 자유롭게 튜닝 가능 (LoRA, Fine-tuning)
- 로컬-개발-상용 모두 같은 LLM 엔진 사용
- 데이터가 외부로 전송되지 않음
- 트래픽 많으면 Bedrock보다 저렴
- 비용 예측 가능 (월 고정)

**대표 인스턴스**: `g5.xlarge` (NVIDIA A10G GPU 24GB, 시간당 ~$1.006)

**운영 시 고려사항**: [01-architecture.md](01-architecture.md) 참고

---

### Apache Tika ★ Phase 0 — 첨부파일 텍스트 추출

**한 줄 설명**: 50+ 파일 포맷에서 텍스트·메타데이터·임베디드 이미지 추출하는 통합 라이브러리.

**우리 시스템에서**:
- FILE 경로 (10-multimodal-files-url.md 섹션 4) 의 텍스트 추출 엔진
- 지원 포맷: PDF / DOCX / PPTX / XLSX / TXT / MD / CSV 등
- 임베디드 이미지 추출 콜백 (`EmbeddedDocumentExtractor`) → Tesseract OCR 로 전달

**보안 옵션**:
- `SecureContentHandler`: 스크립트 비활성화
- 압축 해제 한도 100MB (ZIP 폭탄 방어)
- 라이선스: Apache 2.0 ✅

**Maven**: `org.apache.tika:tika-parsers-standard-package:2.9.x`

---

### Tesseract OCR ★ Phase 0 — 이미지 안 글자 추출

**한 줄 설명**: 오픈소스 OCR 엔진. 한국어 + 영어 지원.

**우리 시스템에서**:
- FILE 경로에서 Tika 가 추출한 임베디드 이미지의 글자 추출
- 언어 데이터: `kor + eng` (`tesseract -l kor+eng`)
- 평균 처리 시간: 이미지당 ~1초 (CPU)
- 한국어 인식 품질: ~90% (일반 비즈니스 문서 기준)

**도입 방법**:
- OS 패키지: `tesseract-ocr`, `tesseract-ocr-kor`, `tesseract-ocr-eng` (Docker 이미지에 사전 설치)
- Java wrapper: **Tess4J** (`net.sourceforge.tess4j:tess4j:5.x`)
- 라이선스: Apache 2.0 (Tesseract), Apache 2.0 (Tess4J) ✅

---

### readability4j ★ Phase 0 — URL 본문 추출

**한 줄 설명**: Mozilla Readability 알고리즘의 Java 포트. HTML 페이지에서 본문 영역만 자동 추출.

**우리 시스템에서**:
- URL_FETCH 경로 (10-multimodal-files-url.md 섹션 3) 의 본문 추출
- 뉴스/블로그/문서 페이지에서 광고·메뉴·푸터 제거하고 본문만 깔끔히 분리
- 출력: clean text + 제목 + 메타데이터

**Maven**: `net.dankito.readability4j:readability4j:1.0.8`
**라이선스**: Apache 2.0 ✅

**SSRF Guard 와 함께 사용 필수** — 10-multimodal-files-url.md 섹션 7 참고.

---

### Spring AI ★ Phase 0 표준 LLM 추상화

**한 줄 설명**: Spring Boot에서 LLM/임베딩/벡터 검색 쓰기 쉽게 해주는 공식 라이브러리.
**버전**: 1.0.x (GA)

**우리 시스템의 적용 영역 (전면 채택)**
| 작업 | Spring AI 컴포넌트 | 비고 |
|------|-------------------|------|
| Chat 생성 (RAG 응답·자연어화·종합) | `ChatClient` + `OllamaChatModel` | SSE 스트리밍 포함 |
| 임베딩 | `EmbeddingModel` (`OllamaEmbeddingModel`) | 동기화/검색 모두 |
| 의도 분류 / SQL 생성 | 같은 `ChatClient` (별도 prompt template) | raw OllamaClient 사용 금지 |
| RAG 결합 | 명시적 retrieval → ChatClient | Advisor도 가능하지만 `access_groups` 동적 필터 때문에 명시 권장 |
| pgvector 쿼리 | **raw SQL 유지** | Spring AI VectorStore는 단순 case만. 우리는 `access_groups && $userGroups` 동적 필터 필요 |

```java
// 어느 환경이든 코드 동일
chatClient.prompt()
    .system(systemPrompt)
    .user(userQuestion)
    .call()
    .content();

// 환경별 차이는 application.yml 설정값만:
// 로컬:  http://localhost:11434
// 개발:  http://dev-server:11434
// 상용:  http://ollama-svc.rag.svc.cluster.local:11434
```

**환경별 application.yml (모델 + 양자화 통일)**
```yaml
# application-local.yml
spring.ai.ollama:
  base-url: http://localhost:11434
  chat.options.model:     qwen2.5:7b-instruct-q4_K_M
  embedding.options.model: bge-m3

# application-dev.yml
spring.ai.ollama:
  base-url: http://ollama-dev.internal:11434
  chat.options.model:     qwen2.5:7b-instruct-q4_K_M   # CPU 추론 감수
  embedding.options.model: bge-m3

# application-prod.yml
spring.ai.ollama:
  base-url: http://ollama:11434
  chat.options.model:     qwen2.5:14b-instruct-q4_K_M       # 텍스트 경로 기본
  embedding.options.model: bge-m3

# VLM 전용 ChatClient — IMAGE 경로용 별도 Bean
rag.vlm.model: qwen2.5-vl:7b-instruct-q4_K_M
# 듀얼 모델 운영, 10-multimodal-files-url.md 섹션 5 참고
```

**왜 raw OllamaClient를 쓰지 않는가**
- Resilience4j Circuit Breaker / Retry는 ChatClient layer에 단일 지점으로 부착 (Phase 1+)
- Metrics(`spring.ai.*`)가 자동 노출 → Prometheus 스크랩
- 로깅·재시도·token usage 추적이 일관됨
- Spring AI 안 쓰면 동등한 인프라를 직접 구현해야 함 (재발명)
- **금지**: `src/main/java/.../OllamaClient.java` 자체 wrapper를 새로 만들지 않는다

---

## 3. 데이터베이스 스택

### MySQL
**한 줄 설명**: 회사가 이미 쓰고 있는 관계형 DB. 원본 데이터 위치.

**역할**: 상품, 계약서, 고객, 트랜잭션 데이터의 원본. 우리는 이걸 읽어서 임베딩만 함.

---

### PostgreSQL + pgvector
**한 줄 설명**: PostgreSQL에 벡터 검색 기능을 추가하는 확장.

```
일반 PostgreSQL: SELECT * WHERE name = '홍길동'
pgvector: SELECT * ORDER BY embedding <=> [질문벡터] LIMIT 5
                                ↑
                          코사인 유사도 검색
```

**왜 이걸 쓰냐**:
- PostgreSQL이 검증된 DB라 안정적
- SQL 그대로 쓰면서 벡터 검색 추가
- RDS에서 지원
- 별도 벡터 DB(Pinecone 등) 안 써도 됨
- Row Level Security로 멀티 테넌시 강화

**대표 연산자**:
- `<->` 유클리디안 거리
- `<=>` 코사인 거리 (가장 자주 사용)
- `<#>` 내적

---

### mysql-binlog-connector-java
**한 줄 설명**: MySQL binlog를 Java로 직접 읽는 오픈소스 라이브러리.

**왜 필요한가**:
- 우리는 회사 MySQL을 미러링하지 않고 binlog로 변경사항 추적
- Debezium 같은 무거운 도구 없이 Spring Boot 안에서 직접 사용
- 변경 이벤트(INSERT/UPDATE/DELETE)를 실시간 또는 배치로 처리

**사용 예시 (GTID 모드)**:
```java
BinaryLogClient client = new BinaryLogClient(
    "customer-mysql-host", 3306, "rag_replicator", "password"
);
// 옵션 B — GTID 전용 추적 (file/position 미사용)
client.setGtidSet(binlogPositionRepo.findLastGtidSet());
client.registerEventListener(event -> {
    EventType type = event.getHeader().getEventType();
    if (type == EventType.UPDATE_ROWS) {
        // 변경 이벤트 처리
    } else if (type == EventType.GTID) {
        // GTID 경계 — 처리 완료 시 binlog_position.gtid_set 업데이트
    }
});
client.connect(5_000);   // 5초 connection timeout
// 30분 cron 주기 동안 처리 → disconnect() → 다음 cron에 다시 connect
```

**회사 MySQL 요구사항**:
- `log-bin` 활성화
- `binlog_format = ROW`
- `binlog_row_image = FULL`
- `gtid_mode = ON`, `enforce_gtid_consistency = ON`  ← 옵션 B (GTID 전용 추적)
- `REPLICATION SLAVE`, `REPLICATION CLIENT` 권한
- binlog 보존 기간 최소 7일

**운영 모드**: 30분 주기 배치 (`@Scheduled(cron = "0 */30 * * * *")`).
24/7 streaming 모드는 Phase 1+ 검토.

---

### VPC Peering / Site-to-Site VPN
**한 줄 설명**: AWS와 외부 네트워크 (고객사 데이터센터 또는 다른 AWS 계정) 간 안전한 연결.

**우리 시스템에서**:
- 회사 MySQL이 별도 서버: 네트워크 직접 연결
- 회사 MySQL이 온프레미스: VPN 또는 직접 연결

**선택 기준**:
| 방식 | 비용/월 | 속도 | 적용 |
|------|--------|------|------|
| VPC Peering | ~$0 | 빠름 | AWS-to-AWS |
| Site-to-Site VPN | ~$36 | 보통 | AWS-to-온프레 |
| Direct Connect | $1,000+ | 매우 빠름 | 대형 고객사 |

→ 고객사별 협의 후 결정.

---

### RDS (AWS)
**한 줄 설명**: AWS의 관리형 데이터베이스 서비스.

```
직접 운영: MySQL 설치, 백업 스크립트, 장애 대응 다 직접
RDS: 클릭 몇 번으로 MySQL 생성, 자동 백업, 장애 시 자동 페일오버
```

**제공 기능**:
- 자동 백업 (포인트 인 타임 복구)
- Multi-AZ 페일오버
- 자동 패치
- 읽기 전용 복제본
- 모니터링 대시보드

---

### Multi-AZ (AWS)
**한 줄 설명**: DB를 두 개의 가용영역에 동시에 두는 것.

```
서울 가용영역 A: 메인 DB
서울 가용영역 C: 대기 DB (실시간 복제)

가용영역 A 다운 → 자동으로 C로 전환 (1~2분)
```

**적용 대상**: RDS PostgreSQL (+ pgvector), ElastiCache (Phase 2+), Amazon MQ (Phase 2+)

---

### S3 (AWS)
**한 줄 설명**: AWS의 파일 저장소. 무한대로 저장 가능.

**역할**:
- 원본 문서 파일(PDF, Word 등) 저장
- RDS에는 텍스트만, 원본 파일은 S3
- 모델 콜드 스토리지

**스토리지 클래스**:
- Standard (자주 접근)
- Intelligent-Tiering (자동 최적화)
- Glacier (장기 보관, 90% 저렴)

---

## 4. 애플리케이션 스택

### Spring Boot
**한 줄 설명**: Java로 웹 API 서버 만드는 프레임워크.

**왜 Java/Spring**:
- 사용자가 Java로 개발하기로 결정
- Spring AI 라이브러리가 LLM/임베딩/pgvector 다 지원
- Spring Security, Spring Data 등 풍부한 생태계
- 엔터프라이즈에서 검증됨

**핵심 모듈**:
- Spring Web (REST API)
- Spring Data JPA (DB 접근)
- Spring Security (인증/인가)
- Spring AI (LLM 통합)
- Spring AMQP (RabbitMQ) — Phase 1+
- ShedLock (분산 스케줄러 락) — Phase 0
- mysql-binlog-connector-java (binlog 기반 데이터 동기화) — Phase 0
- Spring Retry (실패 재시도)
- Flyway (DB 마이그레이션)

---

## 5. 메시지 큐 / 캐시

### RabbitMQ — Phase 1+에서 도입 예정
> Phase 0 MVP에선 Spring `@Scheduled` + ShedLock으로 대체.
> 파일 업로드, 실시간 동기화 등 사용자 트리거 비동기 작업 발생 시 도입.

**한 줄 설명**: 오픈소스 메시지 브로커. 비동기 작업 처리에 사용.

```
임베딩 처리는 시간이 오래 걸림
사용자가 기다리면 안 됨

해결:
"이거 임베딩 처리해" 요청 → RabbitMQ에 넣기
즉시 사용자에게 "접수됐습니다" 응답
백그라운드 워커가 큐에서 꺼내서 처리
```

**왜 RabbitMQ**:
- 어디서든 동일하게 동작 (Dev/Prod Parity)
- Spring AMQP로 쉽게 통합
- 메시지 라우팅, 우선순위, DLQ 풍부
- 관리 UI (Management Plugin)로 큐 상태 직관적 확인

**핵심 개념**:
- Exchange: 메시지 라우터
- Queue: 메시지 저장소
- Binding: Exchange ↔ Queue 연결 규칙
- DLX (Dead Letter Exchange): 실패 메시지 라우팅

---

### Amazon MQ for RabbitMQ — Phase 2+에서 검토
> Phase 1+에서 RabbitMQ 도입 시 초기엔 Docker 컨테이너, 트래픽 증가 시 Amazon MQ 전환.

**한 줄 설명**: AWS가 관리해주는 RabbitMQ 서비스.

```
직접 운영: EC2에 RabbitMQ 설치, 백업, 클러스터링 직접
Amazon MQ: 클릭 몇 번으로 RabbitMQ 띄움, AWS가 관리
```

**왜 SQS 대신 RabbitMQ**:
- 로컬/개발/상용 모두 같은 메시지 큐 사용 가능
- Spring AMQP로 코드 통일
- 라우팅, 우선순위, DLQ 등 풍부한 기능
- 큐 이름, exchange 설계가 환경 무관하게 동일

---

### Redis
**한 줄 설명**: 인메모리 키-값 저장소. 캐시, 세션, 레이트 리밋, 분산 락 용도.

```
응답 속도가 느린 LLM 호출 결과를 Redis에 저장
같은 질문 또는 유사 질문 → 캐시에서 즉시 반환
```

**용도**:
- LLM 응답 캐싱
- 시맨틱 캐시 (질문 임베딩 + 응답) — Phase 1+
- 레이트 리밋 카운터 (테넌트별, 사용자별)
- 세션 저장 (분산 환경)
- **ShedLock 분산 락 저장소** (Phase 0 핵심 용도)

---

### ShedLock
**한 줄 설명**: Spring `@Scheduled` 메서드를 여러 인스턴스에서도 한 번만 실행되게 보장하는 라이브러리.

```java
@Scheduled(cron = "0 */30 * * * *")             // 30분 주기 (옵션 B)
@SchedulerLock(name = "binlogSync", lockAtMostFor = "20m")
public void runBinlogSync() {
    // 여러 Pod이 있어도 한 Pod만 실행됨
    // 락은 Redis에 저장
}
```

**왜 필요한가**:
- Spring Boot 컨테이너가 여러 개일 때 스케줄러 중복 실행 방지
- RabbitMQ 없이도 분산 환경에서 안전한 스케줄링

**락 저장소 선택**: Redis (이미 캐시용으로 사용 중)

---

### ElastiCache (AWS)
**한 줄 설명**: AWS의 관리형 Redis/Memcached 서비스.

```
직접 운영: EC2에 Redis 설치, 클러스터링, 페일오버 직접
ElastiCache: 클릭 몇 번으로 Redis 클러스터 생성, AWS가 관리
```

**제공 기능**:
- Multi-AZ 자동 페일오버
- 클러스터 모드 (샤딩)
- 자동 백업
- 암호화 지원

---

## 6. 컨테이너 / 배포

### Docker
**한 줄 설명**: 앱을 "컨테이너"로 패키징해서 어디서든 똑같이 돌아가게 하는 도구.

```
"내 컴퓨터에서는 되는데 서버에서는 안 돼" 문제 해결
컨테이너 = 미니 가상 컴퓨터
```

**핵심 개념**:
- 이미지(Image): 컨테이너의 설계도
- 컨테이너(Container): 실행 중인 이미지
- Dockerfile: 이미지 빌드 명세
- 레지스트리: 이미지 저장소 (Docker Hub, ECR)

---

### Docker Compose
**한 줄 설명**: 여러 컨테이너(DB, 앱, Ollama 등)를 한번에 관리하는 도구.

```bash
docker compose up    # 모든 서비스 한번에 시작
docker compose down  # 한번에 종료
```

**용도**: 로컬/개발 환경에서 여러 서비스를 묶어서 관리.

---

### ECS Fargate (AWS) — 참고만 (현재 아키텍처에선 미사용)
> 현재 시스템은 Docker Compose를 사용. ECS Fargate는 대안으로 검토되었으나 채택되지 않음.

**한 줄 설명**: AWS가 컨테이너를 알아서 돌려주는 서비스.

```
EC2: 서버를 빌려서 내가 직접 관리 (Linux, Docker 다 설치)
ECS Fargate: 컨테이너만 던져주면 AWS가 알아서 실행
            → 서버 관리 안 함 (Serverless)
```

**왜 Fargate**:
- 서버 관리 불필요
- 사용량 기반 과금
- Auto Scaling 통합
- ALB와 자연스러운 연결

---

### ECR (Elastic Container Registry)
**한 줄 설명**: AWS의 Docker 이미지 저장소.

```
DockerHub의 AWS 버전
- VPC 내부 통신 (빠름, 안전)
- IAM 통합
- 이미지 스캔 기능
```

---

## 7. AWS 인프라 스택

### EC2 (Elastic Compute Cloud)
**한 줄 설명**: AWS의 가상 서버.

**우리 시스템에서의 용도**: Ollama LLM 서버 (GPU 인스턴스).

**인스턴스 타입 (GPU)**:
| 타입 | GPU | VRAM | 용도 |
|------|-----|------|------|
| g4dn.xlarge | T4 | 16GB | 7B 모델 |
| g5.xlarge | A10G | 24GB | 14B 모델 (★ 추천) |
| g5.2xlarge | A10G | 24GB | 14B 모델 + 여유 |
| g5.4xlarge | A10G | 24GB | 32B 모델 |

**과금 모델**:
- On-Demand: 시간당 정가
- Spot: 70% 저렴, 회수 가능
- Reserved: 1~3년 약정, 40~75% 저렴

---


---

### Auto Scaling Group (ASG)
**한 줄 설명**: EC2 인스턴스를 자동으로 늘리고 줄여주는 그룹.

```
[설정 예시]
최소: 2대, 최대: 10대, 평상시: 2대

GPU 사용률 70% 이상 → 1대 추가
GPU 사용률 30% 이하 → 1대 제거
```

---

### ALB (Application Load Balancer)
**한 줄 설명**: 여러 서버에 트래픽을 골고루 나눠주는 AWS 서비스.

```
사용자 100명
    ↓
   ALB
   /  \
앱1   앱2   ← 50명씩 분산
```

**우리 시스템에서**:
- 외부 ALB: 인터넷 → Spring Boot
- 내부 ALB: Spring Boot → Ollama EC2 그룹

**Multi-AZ 의무 (AWS 강제)**:
- ALB는 최소 2개 AZ의 Public Subnet에 attach해야 생성됨
- "Single AZ ALB"는 옵션이 존재하지 않음
- 시간당 요금은 AZ 수와 무관 — Multi-AZ여도 비용 추가 없음
- 우리 시스템은 AZ-a + AZ-c 양쪽 Public Subnet에 ALB 배치
- 컴퓨트(EC2)는 비용 절감을 위해 Single AZ로 운영하지만, ALB는 자동 Multi-AZ

---

### CloudFront (CDN)
**한 줄 설명**: AWS의 글로벌 CDN. 정적 파일을 전세계 엣지 서버에 캐싱.

```
서울 사용자 → 서울 엣지 서버에서 즉시 응답
도쿄 사용자 → 도쿄 엣지 서버에서 즉시 응답
미국 사용자 → 미국 엣지 서버에서 즉시 응답
```

---

### Route 53
**한 줄 설명**: AWS의 DNS 서비스. 도메인 이름을 IP로 변환.

```
api.company.com → ALB의 IP로 자동 연결
```

---

### SES (Simple Email Service)
**한 줄 설명**: AWS의 트랜잭션 메일 발송 서비스.

**우리 시스템에서의 용도**:
- Open WebUI 비밀번호 재설정 메일
- 회원가입 승인 알림 메일
- API Key 만료 임박 알림(추가 채널, Phase 1+)

**구성**:
- 발신 도메인: `noreply@{customer}.ragservice.com`
- 도메인 검증: Route 53 cross-account 자동 (우리 회사 Route 53 → 고객사 SES)
- DKIM/SPF 자동 (SES 활성화 시 Route 53 레코드 추가)
- 리전: 운영 서버의 `ap-northeast-2`

**비용**: EC2에서 발송 시 첫 62,000건/월 무료. 이후 1,000건당 $0.10 → Phase 0엔 사실상 $0.

**환경별 사용**:
- 로컬·개발: **메일 발송 비활성화** (SMTP 환경변수 미설정, Open WebUI의 비밀번호 재설정 기능 OFF, 필요 시 관리자 Admin Panel에서 수동 처리)
- 상용: SES SMTP 사용 (Secrets Manager에서 자격증명 주입)

**Open WebUI SMTP 설정 (상용만)**:
```yaml
SMTP_HOST: email-smtp.ap-northeast-2.amazonaws.com
SMTP_PORT: 587
SMTP_USER: <SES SMTP 자격증명, Secrets Manager>
SMTP_PASS: <Secrets Manager>
SMTP_FROM: noreply@{customer}.ragservice.com
SMTP_STARTTLS: "true"
```

**샌드박스 → Production**: Phase 0은 SES 샌드박스(200건/일)로 충분. Phase 1+ 다중 고객사 시점에 Production 신청.

---

### EventBridge
**한 줄 설명**: AWS의 스케줄러 + 이벤트 버스. cron 작업 + 이벤트 처리.

**역할**: 정기 작업 스케줄링. 단, 우리 시스템의 binlog 동기화는 Spring `@Scheduled` + ShedLock으로 처리(30분 주기). EventBridge는 인프라 수준 잡(예: 일일 백업 검증) 용도.

---

### CloudWatch
**한 줄 설명**: AWS의 통합 모니터링 서비스.

**기능**:
- Logs (애플리케이션/시스템 로그)
- Metrics (CPU, 메모리, GPU 등)
- Alarms (임계값 초과 시 알림)
- Dashboards (시각화)

---

### CodeDeploy — 참고만 (현재 아키텍처에선 미사용)
> 현재 시스템은 Jenkins + Docker Compose로 배포. CodeDeploy는 ECS 환경에서 사용.

**한 줄 설명**: AWS의 자동 배포 도구.

**용도**:
- ECS 서비스 무중단 배포
- EC2 그룹 Blue/Green 배포
- 카나리 배포 (점진적 트래픽 전환)
- 실패 시 자동 롤백

---

## 8. 보안 / 네트워크

### VPC (Virtual Private Cloud)
**한 줄 설명**: AWS 내 격리된 사설 네트워크.

```
VPC = 우리 회사 전용 네트워크 공간
서브넷 = VPC 안의 IP 대역 분할
보안 그룹 = 가상 방화벽
```

**일반 구조**:
- Public Subnet: 인터넷 접근 가능 (ALB)
- Private Subnet: 내부만 (앱, DB)
- Isolated Subnet: 외부 접근 완전 차단 (DB)

---

### Secrets Manager (AWS)
**한 줄 설명**: 비밀번호, API 키를 안전하게 보관하는 서비스.

```
나쁜 방법: application.yml에 비밀번호 저장 → GitHub에 올라가면 끝
좋은 방법: Secrets Manager에 저장 → 앱이 실행 시 자동으로 가져옴
```

**기능**:
- 자동 비밀번호 회전
- IAM 기반 접근 제어
- 암호화 저장 (KMS 통합)
- 감사 로그

---

### WAF (Web Application Firewall)
**한 줄 설명**: 웹 공격(SQL Injection, XSS)을 자동으로 차단하는 방화벽.

**제공 규칙**:
- AWS 관리형 규칙 (OWASP Top 10)
- IP 레이트 리밋
- 지역 차단
- 커스텀 규칙

---

### IAM (Identity and Access Management)
**한 줄 설명**: AWS 자원에 대한 접근 권한 관리 서비스.

**핵심 개념**:
- User: 사람 사용자
- Role: 일시적 권한 (EC2, ECS 등이 사용)
- Policy: 권한 정의 (JSON)
- 최소 권한 원칙: 필요한 만큼만 부여

---

## 9. IaC / CI/CD / 배포 도구

---

### Jenkins
**한 줄 설명**: 오픈소스 CI/CD 자동화 서버. 빌드/테스트/배포 파이프라인 구축.

**구조**:
```
Jenkins Master (EC2 t3.medium)
├── Jenkinsfile (파이프라인 정의)
├── 플러그인 (Git, Docker, SSH 등)
└── 빌드 작업 큐
```

**Jenkinsfile 예시**:
```groovy
pipeline {
    agent any
    stages {
        stage('Build') { steps { sh './gradlew build' } }
        stage('Test')  { steps { sh './gradlew test' } }
        stage('Docker'){ steps { sh 'docker build -t app:${BUILD_NUMBER} .' } }
        stage('Deploy'){ steps { sh 'docker compose -f docker-compose.prod.yml up -d' } }
    }
    post {
        success { discordNotify('배포 성공') }
        failure { discordNotify('배포 실패') }
    }
}
```

**왜 Jenkins**:
- 자체 호스팅 (외부 서비스 의존 X)
- 1,800+ 플러그인
- 검증된 안정성 (대기업 표준)

**우리 시스템 배포 위치**: 회사 사무실 서버 (온프레)
- Jenkins → AWS 배포 권한 → 운영 서버 → 배포
- 인터넷에서 접근 불가, 회사 내부에서만 운영

---

### AWS  Role (배포 권한)
**한 줄 설명**: 한 AWS 계정에서 다른 AWS 계정의 자원에 임시로 접근할 수 있게 해주는 메커니즘.

**왜 필요한가**:
- 사내 서비스 모델에서 고객사마다 AWS 계정이 다름
- 우리 Jenkins (회사 IAM 사용자) → 고객사 계정에 배포해야 함
- 직접 계정 비밀번호 공유 불가 →  Role 사용

**동작 흐름**:
```
[우리 회사 AWS 계정]
- Jenkins용 IAM 사용자 (또는 IAM Role)

[운영 서버]
- IAM Role 생성: DeployRole
- Trust Policy: 우리 회사 AWS 계정 신뢰
- Permissions Policy: VPC, EC2, RDS 등 필요 권한

[배포 시]
1. Jenkins → AWS 배포 권한 API 호출
   Role ARN: arn:aws:iam::고객사ID:role/DeployRole
2. STS가 임시 자격증명 발급 (1시간 유효)
3. Jenkins가 임시 자격증명으로 고객사 계정에 인프라 관리 도구 적용
4. 작업 완료 후 자격증명 자동 만료
```

**인프라 관리 도구 예시**:
```hcl
provider "aws" {
  region = "ap-northeast-2"
  
  assume_role {
    role_arn = "arn:aws:iam::${var.customer_account_id}:role/DeployRole"
  }
}
```

**보안 이점**:
- 영구 자격증명 노출 없음
- 작업별 임시 권한
- CloudTrail에 누가 무엇을 했는지 추적
- 고객사가 언제든 우리 접근 차단 가능

---

### Docker Compose (프로덕션)
**한 줄 설명**: EC2 단일 호스트에서 docker compose를 사용해 프로덕션 운영.

로컬 개발과 동일한 방식으로 프로덕션도 운영 — Dev/Prod Parity 극대화.

```bash
docker compose -f docker-compose.prod.yml up -d --remove-orphans
```

**왜 이걸 쓰냐**:
- Kubernetes 없이도 300명 규모 운영 충분 (Docker Compose로 대체)
- 로컬/개발/상용 모두 같은 도구 (학습 비용 없음)
- EC2 단일 호스트에서 충분한 성능
- 운영 단순화 (컨테이너 오케스트레이터 불필요)

---

### docker-compose.prod.yml
**한 줄 설명**: ECR 이미지 기반 프로덕션 compose 파일.

**구조**:
```
docker-compose.prod.yml   ← 프로덕션 서비스 정의 (ECR 이미지)
.env.prod                 ← 환경변수 (Secrets Manager에서 주입)
```

**핵심 명령**:
```bash
docker compose -f docker-compose.prod.yml up -d    # 배포/업데이트
docker compose -f docker-compose.prod.yml down     # 중단
docker compose -f docker-compose.prod.yml config   # 설정 검증 (docker compose config 대체)
```

**환경별 파일**:
- 로컬: `docker-compose.dev.yml`
- 상용: `docker-compose.prod.yml` (ECR 이미지, Secrets Manager 환경변수)

---

### docker compose 주요 명령어
**한 줄 설명**: 컨테이너 운영에 필요한 핵심 docker compose 명령.

```bash
docker compose ps                          # 컨테이너 목록 및 상태
docker compose logs spring-boot            # 컨테이너 로그
docker compose exec spring-boot bash       # 컨테이너 접속
docker compose pull                        # 최신 이미지 pull
docker compose up -d                       # 배포/업데이트 (무중단)
docker compose down                        # 중단
docker compose stop spring-boot            # 특정 서비스 중단
```

ECR은 [섹션 6 컨테이너/배포](#6-컨테이너--배포) 참고.

---

## 10. 모니터링 / 알람

### CloudWatch
**한 줄 설명**: AWS의 통합 모니터링 서비스 (인프라 메트릭/로그).

**기능**:
- Metrics: EC2 CPU/메모리, RDS, ALB 등
- Logs: 시스템 로그
- Alarms: 임계값 초과 시 SNS 발송
- Dashboards: 시각화

---

### Prometheus
**한 줄 설명**: 오픈소스 시계열 메트릭 수집/저장 도구.

**역할**: Docker Compose 호스트에서 실행되어 모든 컨테이너의 메트릭을 주기적으로 수집.

**Spring Boot 연동**:
```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: prometheus
```
→ `/actuator/prometheus` 엔드포인트 자동 노출
→ Prometheus가 스크랩하여 저장

---

### Grafana
**한 줄 설명**: Prometheus 데이터를 대시보드로 시각화하는 도구.

**용도**:
- 앱 응답시간 그래프
- 큐 길이 추이
- GPU 사용률
- 사용자별 사용량

**Docker Compose 기동 (한 줄)**:
```bash
docker compose -f docker-compose.prod.yml up -d prometheus grafana
```

---

### Loki (Phase 1+)
**한 줄 설명**: Grafana Labs가 만든 로그 수집/저장 도구.

**vs Elasticsearch**:
- 비용 1/10
- 검색 속도 비슷
- Grafana 통합 (한 화면에서 메트릭 + 로그)

---

### Discord Webhook
**한 줄 설명**: Discord 채널로 자동 알림 보내는 URL.

**설정**:
```
1. Discord 서버 설정 → 통합 → 웹후크
2. 새 웹후크 → 채널 선택 (#alerts)
3. URL 복사
4. CloudWatch/Grafana에 등록
```

**알람 발송 예시 (Lambda)**:
```python
import requests

def lambda_handler(event, context):
    webhook = "https://discord.com/api/webhooks/..."
    requests.post(webhook, json={
        "content": f"🚨 {event['AlarmName']}: {event['NewStateReason']}"
    })
```

---

## 부록: 환경별 스택 요약

| 카테고리 | 로컬 | 개발 서버 | 운영 인스턴스 (AWS) |
|---------|------|----------|---------------------|
| **Web UI** | Open WebUI (Docker) | Open WebUI (Docker) | Open WebUI (Docker 컨테이너) |
| **CLI Tool** | — | — | (Phase 2로 연기) |
| 앱 서버 | Spring Boot (IDE) | Spring Boot (Docker) | Docker 컨테이너 |
| LLM 서버 | Ollama (Docker) | Ollama (Docker) | EC2 + Ollama (Spot) |
| 임베딩 모델 | bge-m3 | bge-m3 | bge-m3 |
| LLM 모델 | qwen2.5:7b | qwen2.5:7b/14b | qwen2.5:14b |
| 원본 DB | MySQL (Docker, 샘플) | 회사 MySQL | 회사 MySQL 직접 연결 (binlog, 미러 없음) |
| 네트워크 (DB 연결) | localhost | localhost | VPC Peering 또는 Site-to-Site VPN |
| 벡터 DB | pgvector (Docker) | pgvector (Docker) | RDS PostgreSQL Multi-AZ + pgvector |
| 캐시 / 분산 락 | Redis (Docker) | Redis (Docker) | Redis (Docker 컨테이너) — ShedLock 락 저장소 겸용 |
| 메시지 큐 | (Phase 0 미사용) | (Phase 0 미사용) | (Phase 0 미사용) — Phase 1+에서 RabbitMQ 도입 |
| 스케줄러 | Spring `@Scheduled` | Spring `@Scheduled` | Spring `@Scheduled` + ShedLock (Redis 락) |
| 컨테이너 오케스트레이션 | Docker Compose | Docker Compose | Docker Compose |
| 파일 저장 | 로컬 | 로컬 | S3 (고객사 계정) |
| 로드밸런서 | (없음) | Nginx | ALB |
| DNS | localhost | dev.ragservice.com | `{customer}.ragservice.com` (Route 53, 우리 도메인) |
| HTTPS | (없음) | Certbot | ACM |
| 비밀 관리 | .env (gitignore) | .env (gitignore) | Secrets Manager (고객사 계정) |
| 모니터링 | 콘솔 로그 | Docker logs | CloudWatch + Prometheus + Grafana |
| 알람 채널 | (없음) | (없음) | Discord Webhook |
| IaC | (없음) | (없음) | 인프라 관리 도구 ( 배포) |
| CI/CD | (없음) | 수동 빌드 | Jenkins (회사 서버 — 온프레) |
| 이미지 저장소 | 로컬 Docker | 로컬 Docker | ECR (우리 회사 공유 계정) |
| AWS 계정 | — | — | **고객사별 분리** (위탁 운영, 고객사 직접 청구) |
| 소스 관리 | Git | Git | GitHub (멀티 레포: rag-backend, rag-infra) |
| 비밀 관리 | .env (gitignore) | .env (gitignore) | Secrets Manager |
| 모니터링 | 콘솔 로그 | Nginx 로그 | CloudWatch |
| 스케줄러 | Spring @Scheduled | Spring @Scheduled | Spring @Scheduled + ShedLock |
| 배포 | IDE 실행 | Docker Compose | Docker Compose (Jenkins) |
