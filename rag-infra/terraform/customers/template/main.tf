# 신규 고객사 배포 템플릿
# 사용: cp -r terraform/customers/template terraform/customers/{customer_id}
#       편집 후 terraform init && terraform plan

terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # TODO: 고객사 배포 시 S3 백엔드 활성화
  # backend "s3" {
  #   bucket  = "rag-terraform-state"
  #   key     = "{customer_id}/terraform.tfstate"
  #   region  = "ap-northeast-2"
  #   encrypt = true
  # }
}

provider "aws" {
  region = var.aws_region

  # Jenkins Cross-Account: 우리 회사 Jenkins → 고객사 AWS 계정
  assume_role {
    role_arn     = "arn:aws:iam::${var.customer_account_id}:role/RagOperatorRole"
    session_name = "rag-deployment-${var.customer_id}"
    external_id  = var.customer_id
  }
}

module "rag_stack" {
  source = "../../modules/rag-stack"

  customer_id         = var.customer_id
  customer_account_id = var.customer_account_id
  aws_region          = var.aws_region
  environment         = var.environment
  vpc_cidr            = var.vpc_cidr

  ec2_app_instance_type = var.ec2_app_instance_type
  ec2_gpu_instance_type = var.ec2_gpu_instance_type
  ec2_app_count         = var.ec2_app_count

  rds_instance_class    = var.rds_instance_class
  rds_allocated_storage = var.rds_allocated_storage
  rds_multi_az          = true  # 절대 false 금지
  db_name               = var.db_name
  db_username           = var.db_username
  db_password           = var.db_password

  alb_idle_timeout  = 600  # HTTP 타임아웃 요구사항 고정

  ses_sender_domain = var.ses_sender_domain
  route53_zone_id   = var.route53_zone_id

  jenkins_account_id = var.jenkins_account_id

  common_tags = {
    Customer    = var.customer_id
    Environment = var.environment
    ManagedBy   = "terraform"
    Project     = "rag-saas"
    CostCenter  = var.customer_id
  }
}
