# RAG Stack 모듈 — 고객사별 전체 인프라
# M7 에서 구현 예정. 현재는 로컬 참조 및 공통 설정만.

terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

locals {
  name_prefix = "${var.customer_id}-rag"

  common_tags = merge(var.common_tags, {
    Customer    = var.customer_id
    Environment = var.environment
    ManagedBy   = "terraform"
    Project     = "rag-saas"
  })
}

# ── 모듈 파일 참조 (M7 구현) ──────────────────────────────────────────────────
# vpc.tf    — VPC, Subnet, Internet Gateway, NAT Gateway, Route Table
# alb.tf    — ALB (Multi-AZ mandatory, idle_timeout=600, ACM)  [ADR-0003]
# rds.tf    — RDS PostgreSQL Multi-AZ + pgvector extension
# ec2.tf    — k3s App 노드 (t3.medium×2), GPU 노드 (g5.xlarge Spot)
# ses.tf    — SES 도메인 검증, Route53 레코드
# iam.tf    — Cross-Account Role, EC2 Instance Profile, 최소 권한
