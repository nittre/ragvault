# 엔지니어링 컨벤션

> 코드·문서 작성 시 핵심 원칙 + 환경별 차이 + 자주 쓰는 명령어 + 디렉토리 구조.

## 핵심 원칙 (코드 작성 시 의무)

1. **단일 출처 원칙** — 비용/일정/임계값을 본문에 적지 말고 권위 출처 인용
2. **모든 LLM 호출은 Spring AI 통해서만** — raw `OllamaClient` 자체 wrapper 금지 (ADR-0004)
3. **모든 벡터 검색에 `access_groups && $userGroups` 필터** — Phase 0 는 자명 매칭이지만 누락 금지 (ADR-0002)
4. **PII 마스킹 일관 적용** — RAG 동기화, SQL 결과(Layer 1+3), 응답 후처리, audit_log
5. **양자화 = Q4_K_M, 모든 환경 통일** (ADR-0004)
6. **HTTP 타임아웃 = 600초** (ALB / Nginx / Spring Boot / Open WebUI)
7. **GTID 전용 binlog 위치 추적** (ADR-0001)
8. **ALB Multi-AZ 의무, 컴퓨트만 Single AZ** (ADR-0003)
9. **7단계 우선순위 + Guard 분리** — 파라미터 결정 시 (ADR-0005)
10. **Phase 0 동기 처리, RabbitMQ 미사용** — 파일 처리 600초 안 cover

자세한 보안 원칙: [security-and-guardrails.md](security-and-guardrails.md)

## 환경별 차이 (요약)

| 항목 | 로컬 | 개발 | 상용 |
|------|------|------|------|
| 모델 (텍스트) | qwen2.5:7b-instruct-q4_K_M | 동일 | qwen2.5:14b-instruct-q4_K_M |
| 모델 (이미지) | qwen2.5-vl:7b-instruct-q4_K_M | 동일 | 동일 |
| Embedding | nomic-embed-text | 동일 | 동일 |
| 양자화 | Q4_K_M | Q4_K_M | Q4_K_M |
| 인프라 | Docker Compose | Docker Compose | k3s + Helm + Terraform |
| LLM 클라이언트 | Spring AI `ChatClient` | 동일 | 동일 |
| SMTP | 비활성 | 비활성 | AWS SES |
| HTTP 타임아웃 | 600s | 600s | 600s (ALB/Nginx/Spring/OpenWebUI) |

상세: [`requirements/01-architecture.md`](../../requirements/01-architecture.md) 섹션 2-1·4-4

## 자주 쓰는 명령어

### 빌드·테스트
```bash
./gradlew clean build
./gradlew test --info
```

### 인프라
```bash
cd rag-infra/terraform/customers/{customer}
terraform init -backend=false
terraform validate
terraform plan
# terraform apply 는 가드레일 통과 후 사용자 명시 승인 필요
```

### Helm
```bash
helm lint rag-infra/helm/rag-backend
helm template rag-backend rag-infra/helm/rag-backend -f values-prod.yaml
```

### 로컬 환경
```bash
docker compose -f docker-compose.dev.yml up -d
docker exec -it ollama ollama pull qwen2.5:7b-instruct-q4_K_M
docker exec -it ollama ollama pull nomic-embed-text
docker exec -it ollama ollama pull qwen2.5-vl:7b-instruct-q4_K_M
```

### 회귀 검증 (`spec-check` 스킬 활용)
```bash
# 비용 단일 출처 회귀
grep -rn "\$435\|\$400" requirements/ docs/

# 옛 binlog 표기
grep -rn "매일 새벽 2시\|binlog_file VARCHAR" requirements/ rag-backend/

# raw OllamaClient 금지
grep -rn "new OllamaClient\|private OllamaClient\|@Autowired.*OllamaClient" rag-backend/

# access_groups 필터 누락
grep -rn "embedding <=>" rag-backend/src/main/java/ | grep -v "access_groups"

# PII 마스킹 누락
grep -rn "chatClient.prompt\|return.*response" rag-backend/src/main/java/ | grep -v "piiMasker.mask"

# Lessons Learned 도메인별 검색
grep -rln "spring.ai\|OllamaChatModel" docs/lessons-learned/
grep -rln "terraform\|aws_lb" docs/lessons-learned/
grep -rln "binlog\|GTID" docs/lessons-learned/
```

## 디렉토리 구조

```
rag-practice/
├── CLAUDE.md                       ← 짧은 진입점 (정책 문서 인덱스)
├── requirements/                   ← 권위 요구사항 문서 (10개)
│   ├── TEAM-OVERVIEW.md
│   └── 01-architecture.md ~ 10-multimodal-files-url.md
├── docs/
│   ├── policies/                   ← 운영 정책 (이 문서 영역)
│   │   ├── team-and-workflow.md
│   │   ├── decisions-and-lessons.md
│   │   ├── security-and-guardrails.md
│   │   └── engineering-conventions.md
│   ├── adr/                        ← Architecture Decision Records (immutable)
│   │   ├── README.md
│   │   ├── 0000-template.md
│   │   └── 0001-*.md ~ 0005-*.md
│   └── lessons-learned/            ← Lessons Learned (append-only)
│       ├── README.md
│       └── 0000-template.md
├── .claude/
│   ├── settings.json               ← 공유 설정 (가드레일 hook 등록)
│   ├── settings.local.json         ← 개인 설정 (gitignore 권장)
│   ├── agents/                     ← 에이전트 5명 정의
│   │   ├── planner.md              (opus)
│   │   ├── backend-engineer.md     (sonnet)
│   │   ├── infra-engineer.md       (sonnet)
│   │   ├── code-reviewer.md        (sonnet)
│   │   └── verifier.md             (sonnet)
│   ├── hooks/
│   │   └── guardrail.py            ← PreToolUse 가드레일
│   └── skills/
│       ├── team-orchestrator/      ← 팀 구성·작업 분배
│       ├── adr-propose/            ← 결정 감지 → ADR 제안
│       ├── spec-check/             ← 결정사항 회귀 검증
│       └── lessons-learned/        ← 에러 기록·재발 방지
└── (실제 코드는 구현 시점에 추가)
    ├── rag-backend/                ← Spring Boot
    └── rag-infra/                  ← Terraform + Helm
```

## 참고

- 권위 출처 인덱스: `CLAUDE.md`
- 관련 정책: [team-and-workflow.md](team-and-workflow.md), [decisions-and-lessons.md](decisions-and-lessons.md), [security-and-guardrails.md](security-and-guardrails.md)
- 모든 요구사항: [`requirements/`](../../requirements/)
- 모든 결정: [`docs/adr/`](../adr/)
- 모든 실패 기록: [`docs/lessons-learned/`](../lessons-learned/)
