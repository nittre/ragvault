variable "customer_id" {
  description = "고객사 ID (영소문자, 숫자, 하이픈만 허용)"
  type        = string
  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.customer_id))
    error_message = "customer_id 는 영소문자·숫자·하이픈만 허용합니다."
  }
}

variable "customer_account_id" {
  description = "고객사 AWS 계정 ID (12자리)"
  type        = string
}

variable "aws_region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "environment" {
  description = "배포 환경"
  type        = string
  default     = "prod"
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment 는 dev, staging, prod 중 하나여야 합니다."
  }
}

variable "vpc_cidr" {
  description = "VPC CIDR 블록"
  type        = string
  default     = "10.0.0.0/16"
}

# ── EC2 ──────────────────────────────────────────────────────────────────────
variable "ec2_app_instance_type" {
  description = "애플리케이션 노드 인스턴스 타입 (k3s)"
  type        = string
  default     = "t3.medium"
}

variable "ec2_gpu_instance_type" {
  description = "GPU 노드 인스턴스 타입 (Ollama)"
  type        = string
  default     = "g5.xlarge"
}

variable "ec2_app_count" {
  description = "App 노드 수"
  type        = number
  default     = 2
}

# ── RDS ──────────────────────────────────────────────────────────────────────
variable "rds_instance_class" {
  description = "RDS 인스턴스 클래스 (PostgreSQL + pgvector)"
  type        = string
  default     = "db.t3.medium"
}

variable "rds_allocated_storage" {
  description = "RDS 스토리지 GB"
  type        = number
  default     = 50
}

variable "rds_multi_az" {
  description = "RDS Multi-AZ 활성화"
  type        = bool
  default     = true
}

variable "db_name" {
  description = "PostgreSQL 데이터베이스 이름"
  type        = string
  default     = "ragdb"
}

variable "db_username" {
  description = "PostgreSQL 마스터 사용자명"
  type        = string
  default     = "raguser"
}

variable "db_password" {
  description = "PostgreSQL 마스터 비밀번호 (Secrets Manager 주입 권장)"
  type        = string
  sensitive   = true
}

# ── ALB ──────────────────────────────────────────────────────────────────────
variable "alb_idle_timeout" {
  description = "ALB idle timeout 초 (HTTP 600s 요구사항 — 변경 금지)"
  type        = number
  default     = 600
}

# ── SES ──────────────────────────────────────────────────────────────────────
variable "ses_sender_domain" {
  description = "SES 발신 도메인 (예: customer.ragservice.com)"
  type        = string
}

variable "route53_zone_id" {
  description = "Route 53 Hosted Zone ID (도메인 검증용)"
  type        = string
}

# ── Jenkins Cross-Account ─────────────────────────────────────────────────────
variable "jenkins_account_id" {
  description = "Jenkins 가 있는 회사 AWS 계정 ID"
  type        = string
}

# ── Discord 알람 ──────────────────────────────────────────────────────────────
variable "discord_critical_webhook_url" {
  description = "Discord Critical 채널 Webhook URL"
  type        = string
  sensitive   = true
  default     = ""
}

variable "discord_warning_webhook_url" {
  description = "Discord Warning 채널 Webhook URL"
  type        = string
  sensitive   = true
  default     = ""
}

# ── 태그 ─────────────────────────────────────────────────────────────────────
variable "common_tags" {
  description = "모든 리소스에 붙는 공통 태그"
  type        = map(string)
  default     = {}
}
