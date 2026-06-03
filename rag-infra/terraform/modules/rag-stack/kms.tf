# KMS 키 + Secrets Manager
# 원칙: 모든 데이터 암호화 at rest (ADR 요구사항)
# 원칙: Key Rotation 활성화 (보안 기본값)

# App 데이터 암호화 KMS (Secrets Manager, S3 등)
resource "aws_kms_key" "app" {
  description             = "${local.name_prefix} — App 데이터 암호화"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  tags                    = merge(local.common_tags, { Name = "${local.name_prefix}-kms-app" })
}

resource "aws_kms_alias" "app" {
  name          = "alias/${local.name_prefix}-app"
  target_key_id = aws_kms_key.app.key_id
}

# RDS 암호화 KMS
resource "aws_kms_key" "rds" {
  description             = "${local.name_prefix} — RDS 암호화"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  tags                    = merge(local.common_tags, { Name = "${local.name_prefix}-kms-rds" })
}

resource "aws_kms_alias" "rds" {
  name          = "alias/${local.name_prefix}-rds"
  target_key_id = aws_kms_key.rds.key_id
}

# EC2 EBS 볼륨 암호화 KMS
resource "aws_kms_key" "ec2" {
  description             = "${local.name_prefix} — EC2 EBS 암호화"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  tags                    = merge(local.common_tags, { Name = "${local.name_prefix}-kms-ec2" })
}

resource "aws_kms_alias" "ec2" {
  name          = "alias/${local.name_prefix}-ec2"
  target_key_id = aws_kms_key.ec2.key_id
}

# Secrets Manager — DB 접속 정보
resource "aws_secretsmanager_secret" "db_password" {
  name                    = "${local.name_prefix}-db-password"
  kms_key_id              = aws_kms_key.app.arn
  recovery_window_in_days = 30
  tags                    = merge(local.common_tags, { Name = "${local.name_prefix}-db-password" })
}

resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id = aws_secretsmanager_secret.db_password.id
  secret_string = jsonencode({
    username = var.db_username
    password = var.db_password
    host     = aws_db_instance.main.address
    port     = 5432
    dbname   = var.db_name
  })
}

# Secrets Manager — RAG Backend API Key
resource "aws_secretsmanager_secret" "api_key" {
  name                    = "${local.name_prefix}-api-key"
  kms_key_id              = aws_kms_key.app.arn
  recovery_window_in_days = 30
  tags                    = merge(local.common_tags, { Name = "${local.name_prefix}-api-key" })
}
