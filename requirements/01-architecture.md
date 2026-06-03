# RAG 시스템 아키텍처 설계

> 회사 MySQL 데이터를 기반으로 한 RAG 시스템.
> **Dedicated Instance per Customer** 모델 — 고객사별 격리된 인프라.

각 스택 상세 설명: [02-stack-reference.md](02-stack-reference.md)

---

## 목차

1. [시스템 개요](#1-시스템-개요)
2. [기술 스택](#2-기술-스택)
3. [사용자 인터페이스 (Open WebUI)](#3-사용자-인터페이스-open-webui)
4. [환경별 아키텍처](#4-환경별-아키텍처)
5. [데이터 흐름](#5-데이터-흐름)
6. [보안](#6-보안)
7. [모니터링 + 로깅](#7-모니터링--로깅)
8. [비용 추정](#8-비용-추정)
9. [인프라 / CI/CD / 리포지토리 구조](#9-인프라--cicd--리포지토리-구조)
10. [신규 고객 온보딩](#10-신규-고객-온보딩)
11. [단계별 도입 계획](#11-단계별-도입-계획)
12. [용어 사전](#12-용어-사전)

---

## 1. 시스템 개요

### 만들 것
회사 MySQL DB의 데이터를 AI가 자연어로 답할 수 있게 해주는 RAG 시스템.
**고객사 전용 인스턴스(Dedicated Instance per Customer)** 형태로 제공.

### 핵심 운영 모델 — MSP (Managed Service Provider)

```
[우리 역할]
SaaS + MSP

- 고객사 AWS 계정에 인프라 배포/운영 (위탁 운영)
- 고객사가 AWS 비용 직접 부담 (투명한 청구)
- 우리는 월 운영비/라이선스 청구
- 고객사별 완전 격리된 인프라
```

### 기능 요구사항

- 회사 기존 MySQL DB 데이터를 RAG화
- 자연어 질의 응답 (Open WebUI)
- 웹 검색 fallback
- **고객사별 격리 (AWS 계정 단위)**
- 컨테이너 기반 배포

### RAG가 뭔지

```
사용자 질문
    ↓
[관련 문서 검색] ← 고객사 MySQL → pgvector
    ↓
[AI에게 문서 같이 전달]
    ↓
AI 답변 (출처 포함)
```

LLM은 회사 데이터 모름. 검색해서 같이 넣어줘야 함.
**RAG = Retrieval(검색) + Augmented(추가) + Generation(생성)**

### 사용자 규모 (고객사 1개당)

- 사용자: ~300명
- 동시 접속 피크: ~30명
- 일일 질의: ~6,000건

---

## 2. 기술 스택

### 2-1. 핵심 컴포넌트

| 역할 | 로컬 | 개발 서버 | 고객사 인스턴스 (AWS) |
|------|------|----------|---------------------|
| **Web UI** | Open WebUI (Docker) | Open WebUI (Docker) | Open WebUI (k3s Pod) |
| **앱 서버** | Spring Boot (IDE) | Spring Boot (Docker) | Spring Boot (k3s Pod × 2) |
| **스케줄러** | Spring `@Scheduled` | Spring `@Scheduled` | Spring `@Scheduled` + ShedLock |
| **LLM 서버** | Ollama (Docker, Metal 가속) | Ollama (Docker, CPU 추론) | EC2 + Ollama (Spot, CUDA) |
| **임베딩 모델** | nomic-embed-text | nomic-embed-text | nomic-embed-text |
| **LLM 모델 (텍스트)** | qwen2.5:7b-instruct-q4_K_M | qwen2.5:7b-instruct-q4_K_M | qwen2.5:14b-instruct-q4_K_M |
| **LLM 모델 (이미지·VLM)** | qwen2.5-vl:7b-instruct-q4_K_M | qwen2.5-vl:7b-instruct-q4_K_M | qwen2.5-vl:7b-instruct-q4_K_M |
| **양자화 표준** | Q4_K_M (모든 환경 통일) | Q4_K_M | Q4_K_M |
| **LLM 클라이언트** | Spring AI `ChatClient` + `OllamaChatModel` | 동일 | 동일 |
| **원본 DB** | MySQL (Docker, 샘플) | 회사 MySQL | 고객사 MySQL 직접 연결 (binlog 기반) |
| **벡터 DB** | pgvector (Docker) | pgvector (Docker) | RDS PostgreSQL + pgvector (Multi-AZ) |
| **네트워크 연결 (고객사 DB)** | localhost | localhost | VPC Peering 또는 Site-to-Site VPN |
| **캐시 / 분산 락** | Redis (Docker) | Redis (Docker) | Redis (k3s Pod) |
| **컨테이너 오케스트레이션** | Docker Compose | Docker Compose | k3s + Helm |
| **로드밸런서** | (없음) | Nginx | ALB |
| **DNS** | localhost | dev.company.com | `{customer}.ragservice.com` (Route 53) |
| **모니터링** | 콘솔 로그 | Docker logs | CloudWatch + Prometheus + Grafana |
| **알람 채널** | (없음) | (없음) | Discord Webhook |
| **IaC** | (없음) | (없음) | **Terraform** (필수) |
| **CI/CD** | (없음) | 수동 빌드 | Jenkins (회사 서버, 온프레) |
| **이미지 저장소** | 로컬 Docker | 로컬 Docker | ECR (우리 회사 공유 계정) |
| **소스 코드 관리** | Git | Git | GitHub (멀티 레포) |

### 2-2. Dev/Prod Parity

```
LLM 엔진 (Ollama)            ─ 모든 환경 동일
임베딩 모델 (nomic-embed)    ─ 모든 환경 동일
캐시 (Redis)                 ─ 모든 환경 동일
벡터 DB (pgvector)           ─ 모든 환경 동일
Web UI (Open WebUI)          ─ 모든 환경 동일

→ 코드 변경 없이 설정값만 바꿔서 환경 전환
```

### 2-3. Phase 0 제외 항목

```
☒ Next.js Web UI (Open WebUI 사용)
☒ CLI Tool (Phase 2로 연기)
☒ 멀티 테넌시 (RLS, tenant_id) — 인프라 격리로 대체
☒ JWT 자체 발급 (Open WebUI가 인증)
☒ RabbitMQ (Spring @Scheduled + ShedLock으로 대체)
☒ API Key 시스템 (CLI와 함께 Phase 2)
```

### 2-4. 스택 상세

각 스택의 상세 설명: [02-stack-reference.md](02-stack-reference.md) 참고

---

## 3. 사용자 인터페이스 (Open WebUI)

### 3-1. Open WebUI를 쓰는 이유

```
✓ ChatGPT 클론 완성품 — 별도 프론트엔드 개발 불필요
✓ Ollama 네이티브 통합
✓ OpenAI 호환 API 지원 (우리 백엔드 연결 가능)
✓ 멀티 사용자 관리 + 인증 내장
✓ 파일 업로드, 대화 히스토리, 코드 하이라이팅 기본 제공
✓ Docker 한 줄로 배포

[Dedicated Instance 모델과의 궁합]
- 고객사 1개 = Open WebUI 1개
- 멀티 테넌시 불필요 (인프라 자체가 격리)
- 화이트라벨링 부담 적음 (고객사 1개라 브랜딩 단순)
```

### 3-2. 우리 백엔드와 통합

Open WebUI는 **OpenAI 호환 API**를 호출함. Spring Boot가 OpenAI API를 흉내내면 됨.

```
[흐름]
사용자 → Open WebUI (UI)
       ↓
       POST /v1/chat/completions (OpenAI 호환)
       ↓
       Spring Boot
       ├── 질문 임베딩 (Ollama nomic-embed-text)
       ├── pgvector 벡터 검색
       └── Ollama LLM 호출 (qwen2.5:14b)
       ↓
       SSE 스트리밍 응답 (OpenAI 표준 포맷)
       ↓
       Open WebUI가 토큰 단위로 화면 표시
```

### 3-3. Open WebUI 설정

```
[관리자 설정]
- 우리가 초기 admin 계정 생성
- 고객사 관리자에게 admin 권한 인계
- 고객사 관리자가 자체적으로 사용자 추가/관리

[OpenAI API 엔드포인트 설정]
Settings > Connections > OpenAI API
URL: http://spring-boot-service:8080/v1
API Key: (Spring Boot가 검증할 키)

[기본 모델 설정]
"company-rag-model" (Spring Boot 내부적으로 qwen2.5:14b 사용)
```

### 3-4. Spring Boot OpenAI 호환 API 명세

```
[엔드포인트]
POST /v1/chat/completions
GET  /v1/models  (지원 모델 목록 반환)

[요청 (OpenAI 표준)]
{
  "model": "company-rag-model",
  "messages": [
    {"role": "user", "content": "A 상품 보증 기간이 얼마야?"}
  ],
  "stream": true
}

[스트리밍 응답 (OpenAI 표준 SSE)]
data: {"id":"...","choices":[{"delta":{"content":"A"}}]}
data: {"id":"...","choices":[{"delta":{"content":" 상품의"}}]}
data: {"id":"...","choices":[{"delta":{"content":" 보증"}}]}
...
data: {"id":"...","choices":[{"delta":{},"finish_reason":"stop"}]}
data: [DONE]
```

### 3-5. CLI는 Phase 2로 연기

```
Phase 0: Open WebUI만
Phase 2: Node.js 기반 CLI Tool 추가
         (Spring Boot OpenAI API 그대로 사용 가능)
```

---

## 4. 환경별 아키텍처

### 4-1. 로컬 개발 환경 (맥북 M3 16GB)

```
┌──────────────────────────────────────────────────┐
│  맥북                                              │
│                                                  │
│  IntelliJ IDEA                                   │
│  └─ Spring Boot (:8080)    ← Hot Reload          │
│     (OpenAI 호환 API + 스케줄러)                  │
│                                                  │
│  Docker Desktop                                  │
│  ┌────────────────────────────────────────┐     │
│  │  docker-compose.yml                     │     │
│  │  ├── open-webui      (:3000)            │     │
│  │  ├── ollama          (:11434)           │     │
│  │  ├── postgres-vector (:5432) pgvector   │     │
│  │  ├── mysql           (:3306) 샘플 데이터 │     │
│  │  └── redis           (:6379)            │     │
│  └────────────────────────────────────────┘     │
└──────────────────────────────────────────────────┘
```

**특징**:
- 개발 패턴: IDE 직접 실행 + 인프라 컨테이너
- 비용 0원
- 응답 속도: 5~10초 (qwen2.5:7b)
- Open WebUI는 컨테이너로 띄움 (빌드 없음, 그냥 사용)

### 4-2. 개발 서버 환경 (회사 서버)

```
┌────────────────────────────────────────────────────┐
│  회사 내부 네트워크                                   │
│                                                    │
│  ┌──────────────────────────┐                     │
│  │  회사 MySQL (기존)        │  실제 운영 데이터     │
│  └──────────┬───────────────┘                     │
│             │ 읽기 전용                            │
│  ┌──────────▼────────────────────────────────┐   │
│  │  개발 서버 (1대)                           │   │
│  │                                            │   │
│  │  Nginx (:80, :443) — SSL                  │   │
│  │   ├─ /        → Open WebUI (:3000)         │   │
│  │   └─ /api/*   → Spring Boot (:8080)        │   │
│  │                                            │   │
│  │  Docker Compose:                           │   │
│  │  ├── Open WebUI                            │   │
│  │  ├── Spring Boot                           │   │
│  │  ├── Ollama                                │   │
│  │  ├── PostgreSQL + pgvector                 │   │
│  │  └── Redis                                 │   │
│  └────────────────────────────────────────────┘   │
│                                                    │
│  접근: dev.ragservice.com                          │
└────────────────────────────────────────────────────┘
```

### 4-3. 고객사 인스턴스 (AWS)

**1개 고객사 = 1개 AWS 계정 = 1세트 인프라**

```
                      [인터넷]
                          │
                          ▼
                ┌──────────────────┐
                │   Route 53       │  customera.ragservice.com
                │   (우리 도메인)   │  → 고객사 ALB
                └────────┬─────────┘
                          │
                          ▼
                ┌──────────────────┐
                │       ALB        │  SSL (ACM)
                │  (Multi-AZ 의무) │  AZ-a, AZ-c 양쪽 Public Subnet에 attach
                │                  │  SSE 지원
                └────────┬─────────┘
                          │
   ┌──────────────────────┼─────────────────────────┐
   │  고객사 AWS 계정 (Customer A's Account)         │
   │  VPC (10.0.0.0/16) — ap-northeast-2 서울        │
   │                                                  │
   │  ┌──────────────────────────────────────────┐  │
   │  │  Public Subnet — AZ-a  +  Public Subnet — AZ-c │
   │  │  └── ALB (AWS가 양쪽 AZ에 ENI 생성)         │  │
   │  │      ※ ALB는 Single AZ 옵션 없음 (AWS 강제) │  │
   │  │      ※ NAT Gateway는 AZ-a에만 1개 (비용 절감) │  │
   │  └──────────────────────────────────────────┘  │
   │                                                  │
   │  ┌──────────────────────────────────────────┐  │
   │  │  Private App Subnet — AZ-a                │  │
   │  │                                          │  │
   │  │  k3s Cluster (일반 노드):                  │  │
   │  │  ┌──────────────────┐  ┌──────────────┐  │  │
   │  │  │ EC2 t3.medium #1 │  │ EC2 t3.medium│  │  │
   │  │  │ (k3s server)     │  │ #2 (agent)   │  │  │
   │  │  │                  │  │              │  │  │
   │  │  │ Pods:            │  │ Pods:        │  │  │
   │  │  │ - Spring Boot    │  │ - Spring Boot│  │  │
   │  │  │ - Open WebUI     │  │ - Redis      │  │  │
   │  │  │ - Prometheus     │  │ - Grafana    │  │  │
   │  │  └──────────────────┘  └──────────────┘  │  │
   │  │                                          │  │
   │  │  ※ 스케줄러: Spring @Scheduled + ShedLock │  │
   │  │     Redis 분산 락으로 다중 Pod 안전성 보장 │  │
   │  └──────────────────────────────────────────┘  │
   │                                                  │
   │  ┌──────────────────────────────────────────┐  │
   │  │  Private LLM Subnet — AZ-a                │  │
   │  │                                          │  │
   │  │  EC2 g5.xlarge × 1 (Spot)                │  │
   │  │  └── k3s agent (gpu=true)                │  │
   │  │      └── Ollama Pod                       │  │
   │  │          - qwen2.5:14b                    │  │
   │  │          - nomic-embed-text               │  │
   │  │                                          │  │
   │  │  Auto Scaling Group:                      │  │
   │  │  - 최소 1, 평상시 1, 최대 3                │  │
   │  │  - Spot 회수 시 새 Spot 자동 부팅 (AMI)    │  │
   │  └──────────────────────────────────────────┘  │
   │                                                  │
   │  ┌──────────────────────────────────────────┐  │
   │  │  Private Data Subnet — Multi-AZ           │  │
   │  │  (AZ-a, AZ-c)                            │  │
   │  │                                          │  │
   │  │  RDS PostgreSQL Multi-AZ + pgvector       │  │
   │  │   - 벡터 청크 저장                          │  │
   │  │   - 동기화 로그 / 감사 로그                 │  │
   │  │   - binlog 위치 추적                       │  │
   │  │   - Open WebUI DB                          │  │
   │  └──────────────────────────────────────────┘  │
   │                                                  │
   │  [고객사 MySQL 연결]                              │
   │  VPC Peering 또는 Site-to-Site VPN                │
   │  → Spring Boot가 binlog 직접 읽기                  │
   │  → 우리 측 MySQL 미러 없음                         │
   └──────────────────────────────────────────────────┘

   부가 서비스 (고객사 계정 내):
   ├── S3 (원본 문서, AMI 백업)
   ├── Secrets Manager (DB 비밀번호 등)
   ├── CloudWatch (로그/메트릭/알람)
   ├── EventBridge (스케줄링)
   └── ACM (SSL 인증서)

   ※ ECR(Docker 이미지)은 우리 회사 공유 계정에서 가져옴
     (Cross-Account 권한으로 pull)
```

### 4-4. 환경별 차이 한눈에

| 항목 | 로컬 | 개발 서버 | 고객사 인스턴스 |
|------|------|----------|---------------|
| 컨테이너 오케스트레이션 | Docker Compose | Docker Compose | k3s |
| Web UI | Open WebUI (Docker) | Open WebUI (Docker) | Open WebUI (k3s Pod) |
| 앱 서버 | IDE 직접 | 컨테이너 | Pod × 2 |
| LLM | Ollama Container (Metal) | Ollama Container (CPU) | EC2 + Ollama (Spot, A10G CUDA) |
| LLM 모델 (양자화 통일 Q4_K_M) | qwen2.5:7b-instruct-q4_K_M | qwen2.5:7b-instruct-q4_K_M | qwen2.5:14b-instruct-q4_K_M |
| LLM 클라이언트 | Spring AI ChatClient | Spring AI ChatClient | Spring AI ChatClient |
| MySQL | Docker (샘플) | 회사 DB | 고객사 MySQL 직접 (binlog) |
| PostgreSQL | Docker | Docker | RDS Multi-AZ |
| 로드밸런서 | (없음) | Nginx | ALB |
| HTTPS | (없음) | Certbot | ACM 인증서 |
| HA | (없음) | (없음) | ALB·RDS Multi-AZ / 컴퓨트 Single AZ (AZ-a) |
| 모니터링 | 콘솔 | Docker logs | CloudWatch + Prometheus + Grafana |
| 비용 | $0 | 사내 인프라 | 고객사당 ~$415/월 (AWS 직불, 8-1 표 기준) |

---

## 5. 데이터 흐름

### 5-1. 데이터 동기화 흐름 (MySQL → 벡터 DB)

> 상세 설계: [03-data-sync-pipeline.md](03-data-sync-pipeline.md)
> 가변적 RAG 테이블 관리, 청킹/PII 마스킹 전략, DDL 반자동 처리, 초기 동기화 등 포함.


```
[1] Spring @Scheduled 실행 (30분 주기, cron: 0 */30 * * * *)
    @SchedulerLock(name = "binlogSync", lockAtMostFor = "20m") ← Redis 분산 락
    → 다중 Pod 환경에서도 한 Pod만 실행
        ↓
[2] binlog_position 테이블에서 마지막 GTID set 조회
    {gtid_set: "uuid-1:1-12345"}
        ↓
[3] 고객사 MySQL에 binlog 클라이언트 연결
    (mysql-binlog-connector-java, setGtidSet(lastGtid))
    VPC Peering 또는 Site-to-Site VPN 경유
        ↓
[4] 마지막 GTID 이후 binlog 이벤트 스트림 읽기
    - WRITE_ROWS  (INSERT)
    - UPDATE_ROWS (UPDATE)
    - DELETE_ROWS (DELETE)
    - TABLE_MAP   (스키마 매핑)
    - GTID        (위치 추적용)
        ↓
[5] 각 이벤트별 처리
    ├── PII 마스킹 (정규식 + NER Phase 1+)
    ├── 청킹 (500 토큰, 50 오버랩)
    ├── Ollama 임베딩 생성
    └── pgvector 저장/갱신/삭제
       (Spring @Async + ThreadPool 병렬)
        ↓
[6] 실패한 이벤트는 binlog_events 테이블 기록
    → Spring Retry로 3회 재시도
    → 3회 누적 실패 시 Discord 알람
        ↓
[7] binlog_position.gtid_set 업데이트 (다음 30분 cron을 위해)
    → 다음 cron 호출은 이 GTID 이후만 처리

[데이터 신선도] ≤ 30분 (정상 운영 시)

※ binlog 활성화 사전 조건 (고객사 MySQL):
   - log-bin 활성화
   - binlog_format = ROW
   - binlog_row_image = FULL
   - gtid_mode = ON
   - enforce_gtid_consistency = ON
   - REPLICATION SLAVE / REPLICATION CLIENT 권한
   - binlog 보존 기간 최소 7일

※ Phase 1+ 검토: RabbitMQ 도입 시 위 [5]를 큐로 분리
```

### 5-2. RAG 질의 흐름

> 상세 설계: [04-rag-search-strategy.md](04-rag-search-strategy.md)
> 거리 함수, Top-K, 유사도 임계값, 동적 설정 관리, 모델 변형 등 포함.


```
[1] 사용자 질문 (Open WebUI)
    "A 상품 보증 기간이 얼마야?"
        ↓
[2] Open WebUI → Spring Boot OpenAI 호환 API
    POST /v1/chat/completions
        ↓
[3] ALB 통과 (SSL 종료)
        ↓
[4] Spring Boot가 API Key 검증
    (Open WebUI에 설정된 키)
        ↓
[5] Rate Limit 체크 (Redis 카운터)
        ↓
[6] 시맨틱 캐시 확인 (Phase 1+)
    ├── 히트 → 즉시 응답
    └── 미스 → 다음 단계
        ↓
[7] 프롬프트 인젝션 패턴 검사 (Phase 1+)
        ↓
[8] 질문 임베딩
    Ollama nomic-embed-text 호출
        ↓
[9] pgvector 벡터 검색
    SELECT * FROM document_chunks
    ORDER BY embedding <=> [질문벡터]
    LIMIT 5
        ↓
[10] 검색 결과를 컨텍스트로 조합
        ↓
[11] Ollama LLM 호출 (qwen2.5:14b)
    System: "검색된 문서를 참고해서 답해. 모르면 모른다고 해."
    Context: [검색된 청크 5개]
        ↓
[12] SSE 스트리밍 응답 (OpenAI 표준 포맷)
        ↓
[13] Open WebUI가 토큰 단위 화면 표시
        ↓
[14] 감사 로그 기록 (DB)
```

---

## 6. 보안

### 6-1. 다층 방어

```
[1] Cloudflare         ← DDoS, 봇 차단 (무료 티어)
[2] ALB                ← SSL/HTTPS 강제
[3] VPC                ← DB 외부 접근 차단
[4] Open WebUI 인증    ← 사용자 로그인 (자체 DB)
[5] Spring Boot API Key← Open WebUI → Spring Boot 인증
[6] AWS 계정 격리       ← 고객사별 완전 분리
[7] Secrets Manager    ← 비밀번호 안전 보관
[8] PII 마스킹         ← 개인정보 보호
[9] Audit Log          ← 모든 접근 기록
[10] Rate Limit         ← Redis 카운터로 API 남용 차단
```

### 6-2. 데이터 격리 (단순화됨)

```
[기존: 멀티 테넌트]
모든 고객사 = 같은 DB
tenant_id로 행 단위 분리 (RLS)
→ 복잡, 실수 위험

[현재: Dedicated Instance]
고객사 A의 DB = AWS 계정 A의 RDS
고객사 B의 DB = AWS 계정 B의 RDS
→ 물리적 격리, 코드 단순
```

### 6-3. AWS 계정 분리

```
[우리 회사 AWS 계정]
- ECR (Docker 이미지 저장소, 모든 고객사 공유)
- IAM (Jenkins용 사용자)
- 도메인 (Route 53)

[고객사 A AWS 계정] (위탁 운영)
- VPC, EC2, RDS, ALB 등
- 우리에게 Cross-Account IAM Role 부여
- AWS 비용은 고객사가 직접 부담

[고객사 B AWS 계정] (위탁 운영)
- 위와 동일 구성
- 다른 AWS 계정
- 다른 청구서
```

### 6-4. 인증 흐름

```
[사용자 로그인 (Open WebUI)]
1. 사용자 → https://customera.ragservice.com
2. Open WebUI 로그인 페이지
3. 이메일/비밀번호 입력
4. Open WebUI가 자체 DB에서 검증
5. 세션 쿠키 발급
   → 이후 채팅 화면 접근

[채팅 요청 (Open WebUI → Spring Boot)]
1. Open WebUI가 Spring Boot 호출
   Authorization: Bearer {API_KEY}
2. Spring Boot가 API Key 검증
3. 응답 반환

※ API Key는 Open WebUI 설정에 저장 (Secrets Manager 사용 가능)
```

### 6-5. PII 마스킹

```
MySQL 원본 데이터 → [마스킹 처리] → 임베딩 → pgvector

[마스킹 대상 (8개) — 03-data-sync-pipeline.md 섹션 5 권위 출처]
✓ 이름, 주민등록번호, 전화번호, 이메일
✓ 주소, 계좌번호, 카드번호, 사번/부서번호

[방식 — 하이브리드]
1차: 정규식 (빠른 명백한 패턴)
2차: NER 모델 (놓친 것 보완, Phase 1+)
```

---

## 7. 모니터링 + 로깅

### 7-1. 모니터링 스택 (고객사별)

```
[CloudWatch] — AWS 인프라 메트릭
├── EC2 CPU/메모리/디스크
├── RDS 연결수/지연시간
├── ALB 요청수/에러율
└── 알람 → SNS → Discord

[Prometheus + Grafana] — 앱 메트릭 (k3s 안)
├── Spring Boot Actuator → Prometheus 스크랩
├── Open WebUI 메트릭
├── GPU 메트릭 (DCGM Exporter)
├── 노드/Pod 메트릭
└── Grafana 대시보드
```

### 7-2. 중앙 관제 (우리 회사 측)

```
[고객사 통합 대시보드]
- 우리 회사 측 Grafana 인스턴스
- 각 고객사 Prometheus를 Federation으로 수집
- 모든 고객사 상태 한눈에 확인

[비교]
고객사 A 응답시간 vs 고객사 B vs 고객사 C
→ 이상 감지 시 알람
```

### 7-3. 알람 정책

```
즉시 알람 (Discord Webhook):
├── API 5xx 에러율 > 5%
├── p99 응답시간 > 5초
├── DB CPU > 80%
├── GPU 메모리 > 90%
├── Spot 인스턴스 회수 알림
├── 데이터 동기화 실패 (3회 누적)
└── Jenkins 빌드/배포 실패

일일 리포트 (Discord):
├── 고객사별 사용량 통계
├── 비용 추이
└── 응답 품질 (Phase 1+)
```

### 7-4. 로깅

```
[CloudWatch Logs] — 인프라 로그
├── EC2 시스템 로그
└── ALB 액세스 로그

[Loki] (Phase 1+) — 앱 로그
├── 모든 Pod 로그 자동 수집
└── Grafana에서 검색

[Audit Log] — 비즈니스 로그 (DB 테이블)
└── "누가 언제 무엇을 검색했는지"
```

---

## 8. 비용 추정

### 8-1. 고객사 1개당 월 AWS 비용 (서울 리전, 고객사 부담)

| 항목 | 스펙 | 월 비용 |
|------|------|--------|
| EC2 t3.medium × 2 (1 OD + 1 Spot) | k3s 일반 노드 | $50 |
| EC2 g5.xlarge × 1 (Spot) | Ollama 전용 | $220 |
| RDS PostgreSQL Multi-AZ + pgvector | db.t3.small | $50 |
| Redis | k3s Pod (캐시 + ShedLock) | $0 |
| ALB | 1개 | $20 |
| 네트워크 (VPN 또는 Peering) | 고객사 MySQL 연결 | ~$30 |
| CloudWatch | 로그/메트릭 | $20 |
| 데이터 전송 | ~100GB | $20 |
| CloudTrail / Config | 보안 감사 | $5 |
| **합계 (고객사당)** | | **~$415/월** |

> ※ 본 표가 비용 견적의 단일 출처(single source of truth). 본문/다른 문서의 인용은 이 표를 참조한다.
> ※ 실제 영업 견적은 견적 시점 AWS 가격 + 마진 포함하여 재산정한다.
>
> RDS MySQL은 고객사 MySQL 직접 연결로 변경 → 미러 불필요 → 비용 $50 절감
> VPN/Peering 비용 $30 추가 → 순 절감 $20/월

### 8-2. 우리 회사 공유 인프라 (전체 고객사 분담)

| 항목 | 스펙 | 월 비용 |
|------|------|--------|
| EC2 t3.medium (Jenkins, 회사 서버라면 $0) | CI/CD | $30 |
| EBS 100GB (Jenkins) | Jenkins 데이터 | $10 |
| ECR | Docker 이미지 저장소 | $5 |
| Route 53 호스팅 영역 | DNS | $1 |
| **합계** | | **~$45/월** |

→ 회사 서버에 Jenkins 두면 $0~10 수준으로 절감 가능.

### 8-3. 비용 모델

```
[고객사가 부담]
- AWS 비용 (~$415/월, 8-1 표 기준) — AWS에 직접 청구
- 데이터 전송, 추가 사용량 등

[우리가 부담]
- ECR, Jenkins 서버 (~$45/월, 고객사 수와 무관)

[우리가 청구]
- 월 라이선스 + 운영비 (예: $1,500~3,000/월)
- 신규 고객 온보딩 비용 (1회성)
- AWS 비용 위탁 관리 마진 (선택)
```

### 8-4. 비용 시나리오

```
고객사 5개 운영 시:
- 고객사 AWS 비용: 5 × $415 = $2,075 (각자 부담)
- 우리 회사 인프라: $45
- 우리 수익: 5 × $2,000 = $10,000 (예시)
- 우리 운영 비용: 인력 + 인프라 + 도구
```

### 8-5. 비용 최적화 팁

```
[즉시 적용]
- GPU Spot 인스턴스 (현재 적용)
- ElastiCache 대신 k3s Redis Pod
- 메시지 큐 미사용 (Phase 0)
- Cloudflare 무료 티어로 WAF 대체

[Phase 1 이후]
- 시맨틱 캐싱으로 LLM 호출 30~50% 감소
- S3 Intelligent-Tiering
- GPU Reserved 1년 약정 (안정화 후)
- RDS Reserved 1년 약정
```

---

## 9. 인프라 / CI/CD / 리포지토리 구조

### 9-1. 인프라 코드화 (IaC) — Terraform

**원칙**: 모든 AWS 리소스는 Terraform으로 정의. 콘솔 클릭 금지.

**Dedicated Instance 모델에서 Terraform이 필수인 이유**:
- 고객사마다 동일한 인프라 반복 배포
- 신규 고객 온보딩 30분 만에 완료
- 모든 고객사에 동일한 구성 보장
- 코드로 인프라 차이 추적

**디렉토리 구조 (rag-infra 리포지토리)**:
```
rag-infra/
├── terraform/
│   ├── modules/
│   │   └── rag-stack/           ← 재사용 모듈 (1개 고객사 인프라)
│   │       ├── vpc.tf
│   │       ├── k3s-nodes.tf
│   │       ├── gpu-node.tf
│   │       ├── rds.tf
│   │       ├── alb.tf
│   │       ├── secrets.tf
│   │       └── monitoring.tf
│   │
│   ├── shared/                  ← 우리 회사 공유 인프라
│   │   ├── ecr.tf
│   │   ├── route53.tf
│   │   └── iam-roles.tf
│   │
│   └── customers/
│       ├── customer-a/
│       │   └── main.tf          ← 고객사 A 변수
│       ├── customer-b/
│       │   └── main.tf
│       └── _template/           ← 신규 고객 템플릿
│
└── helm/
    ├── rag-backend/             ← Spring Boot Helm 차트
    ├── open-webui/              ← Open WebUI Helm 차트
    ├── ollama/                  ← Ollama Helm 차트
    └── monitoring/              ← Prometheus/Grafana 차트
```

**고객사별 Terraform 변수 예시**:
```hcl
# terraform/customers/customer-a/main.tf
module "rag_instance" {
  source = "../../modules/rag-stack"
  
  customer_name      = "customer-a"
  customer_domain    = "customera.ragservice.com"
  aws_account_id     = "111122223333"           # 고객사 A AWS 계정
  aws_role_arn       = "arn:aws:iam::111122223333:role/RagOperatorRole"
  region             = "ap-northeast-2"
  
  instance_size      = "small"
  llm_model          = "qwen2.5:14b"
  data_retention_days = 365
}
```

### 9-2. CI/CD — Jenkins (회사 서버)

**구성**:
```
회사 서버 (온프레)
└── Jenkins Master
    ├── Spring Boot 빌드 파이프라인
    ├── Open WebUI 이미지 동기화 (upstream pull)
    ├── Ollama 이미지 빌드 (모델 사전 포함)
    ├── Terraform plan/apply 파이프라인
    └── Helm 배포 파이프라인 (고객사별)
```

**Cross-Account 배포 흐름**:
```
[1] Jenkins 빌드 시작
[2] AWS STS AssumeRole
    → 고객사 A의 Cross-Account Role
    → 임시 자격증명 획득 (1시간 유효)
[3] Terraform apply (고객사 A 계정에 배포)
[4] Helm upgrade (k3s에 새 이미지 배포)
[5] Discord 알람
```

**Jenkinsfile 예시**:
```groovy
pipeline {
    parameters {
        choice(name: 'CUSTOMER', choices: ['customer-a', 'customer-b'])
        choice(name: 'COMPONENT', choices: ['rag-backend', 'ollama', 'open-webui'])
    }
    stages {
        stage('Build & Push to ECR') { /* 우리 회사 ECR */ }
        stage('Assume Customer Role') {
            steps {
                withAWS(role: customerRoleArn(params.CUSTOMER)) {
                    stage('Terraform Apply') { /* 고객사 계정 */ }
                    stage('Helm Deploy') { /* 고객사 k3s */ }
                    stage('Smoke Test') { /* 헬스체크 */ }
                }
            }
        }
    }
    post {
        always { discordNotify() }
    }
}
```

**브랜치 전략**:
```
main      → 고객사별 수동 트리거로 배포
develop   → 개발 서버 자동 배포
feature/* → PR 빌드/테스트만
```

### 9-3. k3s 배포 — Helm 차트

**Helm 차트 구조**:
```
helm/rag-backend/
├── Chart.yaml
├── values.yaml              ← 기본값
├── values-customer-a.yaml   ← 고객사별 오버라이드 (있다면)
└── templates/
    ├── deployment.yaml
    ├── service.yaml
    ├── ingress.yaml
    ├── configmap.yaml
    ├── secret.yaml
    ├── hpa.yaml
    └── servicemonitor.yaml
```

**배포 명령**:
```bash
helm upgrade --install rag-backend ./helm/rag-backend \
  -f values-customer-a.yaml \
  --set image.tag=v1.2.3 \
  --namespace rag

helm rollback rag-backend     # 롤백
```

### 9-4. 리포지토리 구조 — 멀티 레포

```
GitHub Organization: company-rag

rag-backend/      ← Spring Boot (OpenAI 호환 API + 스케줄러)
                    Java/Gradle, Spring AI
                    Jenkinsfile

rag-infra/        ← Terraform + Helm + Open WebUI 설정
                    인프라 코드, K8s 매니페스트
                    Jenkinsfile (terraform apply)

rag-cli/          ← Node.js CLI (Phase 2)
                    [Phase 0~1: 미존재]
```

> Phase 0에서는 `rag-backend`와 `rag-infra` 2개로 시작.

---

## 10. 신규 고객 온보딩

### 10-1. 온보딩 흐름

```
[1] 영업 계약 체결
        ↓
[2] 고객사가 AWS 계정 생성
    (기존 계정 사용 또는 신규 생성)
        ↓
[3] 고객사가 우리에게 IAM Cross-Account Role 부여
    - Role 이름: RagOperatorRole
    - 신뢰 관계: 우리 회사 AWS 계정
    - 권한: AdministratorAccess
            (또는 필요 권한만 최소화)
        ↓
[4] 우리: 고객사 정보 등록
    - terraform/customers/{customer-id}/ 디렉토리 생성
    - 변수 설정 (customer_name, aws_role_arn, instance_size, llm_model 등)
        ↓
[5] Jenkins에서 배포 트리거
    Jenkins → AssumeRole → 고객사 계정 → terraform apply
        ↓
[6] 인프라 자동 구성 (30분 ~ 1시간)
    - VPC, 서브넷, 보안 그룹
    - EC2, RDS Multi-AZ
    - k3s 클러스터
    - ALB + ACM 인증서
    - Open WebUI + Spring Boot + Ollama 배포
        ↓
[7] 도메인 설정
    - Route 53 (우리 도메인): customera.ragservice.com → 고객사 ALB
    - ACM 인증서 자동 발급 및 검증
        ↓
[7-1] SES 도메인 검증 (메일 발송용)
    - 고객사 AWS 계정에서 SES 활성화 (ap-northeast-2)
    - 발신 도메인 `noreply@customera.ragservice.com` 등록
    - Route 53 cross-account 으로 DKIM/SPF/MAIL FROM 레코드 자동 추가
    - SES SMTP 자격증명 생성 → Secrets Manager(`ses-smtp-secret`)에 저장
    - Phase 0 은 샌드박스 모드 (200건/일) — 첫 고객사 300명에 충분
    - 02-stack-reference.md 'SES' 섹션 참고
        ↓
[8] 초기 데이터 동기화
    - 고객사 MySQL 연결 정보 등록 (Secrets Manager)
    - 첫 풀 동기화 실행 (시간 변동)
        ↓
[9] Open WebUI 관리자 계정 생성
    - 우리가 초기 admin 계정 1개 생성
    - 고객사 관리자에게 인계
        ↓
[10] 고객사에게 접속 정보 전달
    - URL: https://customera.ragservice.com
    - 초기 관리자 계정 정보
        ↓
[11] 고객사 자체적으로 사용자 추가/관리 시작
```

**총 소요 시간**: 1~2일 (DNS 검증 + 데이터 마이그레이션 시간 포함)

### 10-2. 도메인 / SSL 설정

```
[Route 53 (우리 회사 계정)]
ragservice.com (Hosted Zone)
├── customera.ragservice.com → 고객사 A의 ALB
├── customerb.ragservice.com → 고객사 B의 ALB
└── ...

[ACM (고객사 계정)]
각 고객사 ALB에 ACM 인증서 연결
└── DNS 검증 자동화
    (우리가 Route 53 Cross-Account로 검증 레코드 추가)
```

### 10-3. 고객사 데이터 백업

**우리 책임**:
```
[자동 백업]
- RDS 자동 백업 (매일 스냅샷, 7일 보관)
- 매주 일요일 풀 백업 → S3 (30일 보관)
- 임베딩은 재생성 가능 → 백업 우선순위 낮음
- 원본 문서 (S3) → 버저닝 활성화

[복구 절차]
- RPO (Recovery Point Objective): 24시간
- RTO (Recovery Time Objective): 4시간
- Multi-AZ 페일오버: 1~2분 자동
```

### 10-4. Open WebUI 업데이트 정책

```
[고객사별 별도 일정]
- Open WebUI는 적극적으로 업데이트되는 오픈소스
- 모든 고객사에 일괄 적용하지 않음
- 고객사 일정에 맞춰 개별 업데이트
- 사전 공지 후 점검 시간에 진행

[프로세스]
1. 우리 회사 검증 환경에서 새 버전 테스트
2. 고객사에 업데이트 가능 안내
3. 고객사 동의 후 점검 시간 협의
4. Jenkins로 해당 고객사만 배포
5. 사후 모니터링
```

---

## 11. 단계별 도입 계획

### Phase 0 — MVP (약 4.5~4.7개월, Open WebUI 사용 + Admin Web UI)

> **마일스톤 분해 (M0~M8)**: [`docs/policies/milestones.md`](../docs/policies/milestones.md) — 각 마일스톤의 Exit criteria · 의존성 · 데모 스토리. Phase 0 가 두루뭉술하지 않도록 통제 단위.

> 작업량 산정:
> - 기존 RAG MVP                                  ≈ 2개월
> - Text-to-SQL/혼합 검색 옵션 B 추가             ≈ 1개월 (31일)
> - URL Fetch + 첨부파일(동기 Tika+OCR) + 멀티모달 ≈ 3주 (10-multimodal-files-url.md 참고)
> - Admin Web UI 7개 화면 (ADR-0009)              ≈ 2~3주
> - CSV 사용자 batch 추가 (admin-journeys.md A2-1) ≈ 1주
> ────────────────────────────────────────────────
> Phase 0 합계                                     ≈ 4.5~4.7개월

> **UX 권위 출처**: [`docs/ux/user-journeys.md`](../docs/ux/user-journeys.md), [`docs/ux/admin-journeys.md`](../docs/ux/admin-journeys.md). 시니어 UX 리서처 점검에서 130+ 결정 누적. 코드 구현 시 1차 장관.

> **외부 SLA — Phase 0 미정.** 영업·법무·CTO 결정 영역.
> 본 문서·06-error-handling.md 의 임계값(Warning/Critical, RPO/RTO, 응답시간 등)은 **내부 SLO 가안**이며,
> 외부 SLA로 확정되기 전까지 운영 기준점으로만 사용한다.
> 첫 고객사는 **베타 — SLA 없음, best effort** 가정.

**목표**: 첫 번째 고객사 베타 출시 (RAG + Text-to-SQL + 혼합)

```
☑ 로컬 개발 환경 (Docker Compose)
☑ Spring Boot OpenAI 호환 API
☑ Open WebUI 통합
☑ MySQL → pgvector 동기화 (Spring @Scheduled)
☑ ShedLock 분산 락
☑ 기본 보안 (VPC, Secrets Manager)
☑ k3s 클러스터 구축 (단일 고객사)
☑ 임베딩 모델 버전 관리 (스키마)
☑ PII 마스킹 (정규식)
☑ 기본 모니터링 (CloudWatch + Prometheus + Grafana)
☑ Terraform 모듈 (재사용 가능)
☑ Jenkins 파이프라인 (Cross-Account 배포)
☑ 신규 고객 온보딩 절차 문서화

[Text-to-SQL + 혼합 검색 (옵션 B 채택)]
☑ 의도 분류기 (**RAG / SQL / HYBRID / URL_FETCH / FILE / IMAGE** — 6경로, 10-multimodal-files-url.md 참고)
☑ URL Fetch — readability4j + SSRF Guard, 동기 처리
☑ 첨부파일 분석 — Apache Tika + Tesseract OCR (kor+eng), 동기 처리, 30MB / 200 images
☑ 멀티모달 채팅 이미지 — qwen2.5-vl:7b-instruct-q4_K_M 듀얼 모델 (옵션 A)
☑ HTTP 타임아웃 전 구간 600초 (ALB / Nginx / Spring Boot / Open WebUI)
☑ S3 lifecycle 24h (첨부파일 Ephemeral)
☑ **Admin Web UI** — `/admin/*` SPA 7개 화면 (ADR-0009): users / rag-tables / sql-tables / search-config / ddl-events / audit-logs / usage-stats + api-keys + param-limits
☑ **사용자 batch CSV upload** (admin-journeys.md A2-1)
☑ **PII 마스킹 — 모든 LLM 응답 경로** (ADR-0008): RAG/SQL/HYBRID/URL/FILE/IMAGE 모두 응답 후처리 Layer 3 적용
☑ **시스템 상태 페이지** — `/status` 정적 HTML (Spring Boot `/api/v1/health/deep` 기반)
☑ 스키마 자동 조회 + Redis 캐시
☑ Text-to-SQL 변환 (Few-shot 프롬프트)
☑ SQL 안전성 검증 (JSqlParser AST)
☑ Read-only MySQL 계정
☑ 쿼리 타임아웃 10초 + 행 제한 1000
☑ 결과 자연어 변환
☑ 혼합 검색 (SQL + RAG 병렬 → 종합 LLM)
☑ sql_table_config 동적 관리

[사용자 파라미터 튜닝 패널]
☑ Open WebUI 포크 + 사이드 패널 UI
☑ 13개 파라미터 노출 (3개 고정)
☑ 사용자 프로필 저장 + 대화별 override
☑ 관리자 한계 설정
☑ 자동 검증 + 툴팁 + 초기화

제외:
☒ Next.js Web UI (Open WebUI로 대체)
☒ CLI Tool (Phase 2)
☒ 멀티 테넌시 (Dedicated 모델로 대체)
☒ RabbitMQ / Worker Pod (Phase 1+)
☒ 시맨틱 캐싱
☒ 프롬프트 인젝션 가드레일
☒ Circuit Breaker
☒ RAG 품질 평가 시스템
☒ SSO
```

> Text-to-SQL + 혼합 상세 설계: [08-text-to-sql.md](08-text-to-sql.md)
> 사용자 파라미터 튜닝 패널: [09-user-parameter-tuning.md](09-user-parameter-tuning.md)

### Phase 1 — 정식 출시 (Phase 0 + 4개월)

**목표**: 다중 고객사 운영

```
추가:
☑ 다중 고객사 자동 온보딩 강화
☑ 고객사 통합 모니터링 대시보드
☑ RabbitMQ + Worker Pod 도입 (파일 업로드 시점)
☑ 프롬프트 인젝션 방어 (Llama Guard)
☑ Circuit Breaker (Resilience4j)
☑ RAG 품질 평가 시스템 (Golden Dataset)
☑ 프롬프트 관리 DB
☑ 시맨틱 캐싱
☑ Loki 로그 통합
☑ 컴플라이언스 (개인정보보호법)
☑ SLA 정의 (고객사별)
☑ NER 모델 기반 PII 마스킹
```

### Phase 2 — 확장 (Phase 1 + 6개월)

**목표**: 엔터프라이즈 + 고도화

```
추가:
☑ CLI Tool 개발 (Node.js)
☑ Open WebUI 포크 + 화이트라벨링 (필요 시)
☑ 하이브리드 검색 (벡터 + BM25)
☑ Re-ranking
☑ 데이터 리니지
☑ EKS 마이그레이션 검토 (트래픽 증가 시)
☑ Multi-AZ 컴퓨트 (요청 시)
☑ ElastiCache 전환 (Redis HA)
☑ Amazon MQ 전환 (RabbitMQ HA)
☑ CDC 실시간 동기화 (Debezium + RabbitMQ)
☑ AWS Organizations + Control Tower (고객사 계정 자동 생성)
```

---

## 12. 용어 사전

| 용어 | 풀이 | 한 줄 설명 |
|------|------|-----------|
| RAG | Retrieval-Augmented Generation | 검색 + AI 답변 |
| LLM | Large Language Model | GPT, Claude 같은 거대 언어 모델 |
| 임베딩 | Embedding | 텍스트를 숫자 벡터로 변환 |
| 벡터 DB | Vector Database | 벡터 유사도 검색 가능한 DB |
| 청킹 | Chunking | 긴 문서를 작게 자르기 |
| Dedicated Instance | - | 고객사 1개당 전용 인프라 (Single-Tenant) |
| MSP | Managed Service Provider | 위탁 운영 사업 모델 |
| Cross-Account Role | - | AWS 계정 간 권한 위임 메커니즘 |
| Open WebUI | - | ChatGPT 클론 오픈소스 챗 UI |
| OpenAI 호환 API | - | OpenAI Chat Completions API 스펙 |
| JWT | JSON Web Token | 인증 토큰 표준 |
| PII | Personally Identifiable Info | 개인 식별 정보 |
| VPC | Virtual Private Cloud | AWS 내 격리된 네트워크 |
| ALB | Application Load Balancer | AWS L7 로드밸런서 |
| RDS | Relational Database Service | AWS 관리형 RDB |
| Multi-AZ | Multi Availability Zone | 다중 가용영역 (HA) |
| Spot Instance | - | AWS 남는 자원을 싸게 빌리는 EC2 |
| AMI | Amazon Machine Image | EC2 부팅용 디스크 이미지 |
| Auto Scaling Group | - | EC2 자동 증감 그룹 |
| pgvector | - | PostgreSQL 벡터 확장 |
| Ollama | - | LLM 실행 도구 |
| Spring AI | - | Spring의 AI 추상화 라이브러리 |
| Redis | - | 인메모리 캐시 + 분산 락 |
| ShedLock | - | Spring `@Scheduled` 다중 Pod 분산 락 |
| `@Scheduled` | - | Spring 내장 스케줄러 |
| `@Async` | - | Spring 내장 비동기 실행 |
| RabbitMQ | - | 메시지 브로커 (Phase 1+ 도입) |
| k3s | - | 경량 Kubernetes 배포판 |
| Pod | - | K8s 최소 배포 단위 |
| Helm | - | K8s 패키지 매니저 |
| IaC | Infrastructure as Code | 인프라를 코드로 관리 |
| Terraform | - | HashiCorp의 IaC 도구 |
| AssumeRole | - | AWS STS 임시 자격증명 발급 |
| Jenkins | - | 오픈소스 CI/CD 서버 |
| CI/CD | Continuous Integration/Delivery | 빌드/배포 자동화 |
| ECR | Elastic Container Registry | AWS Docker 이미지 저장소 |
| Discord Webhook | - | Discord 채널 알림 URL |
| Cloudflare | - | DDoS 방어/DNS (무료 티어) |
| 멀티 레포 | Multi-Repository | 컴포넌트별 별도 리포 |
| RPO | Recovery Point Objective | 데이터 손실 허용 시간 |
| RTO | Recovery Time Objective | 서비스 복구 목표 시간 |

---

## 다음 단계

이 아키텍처 기반으로 다음 결정:

1. **A. 데이터 동기화 파이프라인 상세 설계** (이전에 시작했던 12개 결정사항)
2. **B. DB 스키마 상세 설계** (tenant_id 없는 단순화된 스키마)
3. **C. 인증/인가 상세 설계** (Open WebUI ↔ Spring Boot API Key)
4. **D. RAG 검색 전략 상세 설계** (청킹, Top-K, 재순위)
5. **E. 프롬프트 설계** (System 프롬프트, 출처 인용)
6. **F. 에러 처리/장애 대응 설계**

A부터 진행할 준비 되면 알려줘.
