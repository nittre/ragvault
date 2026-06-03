# rag-infra

RAG SaaS 인프라 코드 (Terraform + Helm).

## 구조

- `terraform/modules/rag-stack/` — 재사용 가능한 고객사 스택 Terraform 모듈 (VPC, ALB, RDS, EC2, SES, IAM)
- `terraform/customers/template/` — 신규 고객사 배포 템플릿
- `helm/rag-backend/` — Spring Boot Helm 차트
- `helm/ollama/` — Ollama Helm 차트

## 자주 쓰는 명령어

### Terraform

```bash
# 고객사 디렉토리로 이동
cd terraform/customers/{customer_id}

terraform init -backend=false    # 로컬 검증용
terraform validate
terraform plan
# terraform apply 는 가드레일 통과 + 사용자 명시 승인 후 실행
```

### Helm

```bash
# 린트
helm lint helm/rag-backend
helm lint helm/ollama

# 템플릿 렌더링 확인
helm template rag-backend helm/rag-backend -f helm/rag-backend/values-prod.yaml
```

## ADR 참고

- ADR-0003: ALB Multi-AZ 의무, 컴퓨트 Single AZ
- ADR-0004: Q4_K_M 양자화 (모든 환경)
- 실제 구현: M7 마일스톤

## 의존성

- Terraform >= 1.5
- Helm >= 3.12
- kubectl >= 1.28 (k3s 호환)
