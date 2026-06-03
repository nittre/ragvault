# RDS PostgreSQL + pgvector
# Multi-AZ 필수 (High Availability)
# 암호화 at rest — KMS 키 분리 (kms.tf)
# pgvector 확장은 Flyway 마이그레이션에서 활성화 (CREATE EXTENSION IF NOT EXISTS vector)

resource "aws_db_subnet_group" "main" {
  name       = "${local.name_prefix}-db-subnet"
  subnet_ids = [aws_subnet.private_data_az_a.id, aws_subnet.private_data_az_c.id]
  tags       = merge(local.common_tags, { Name = "${local.name_prefix}-db-subnet" })
}

resource "aws_db_parameter_group" "postgres" {
  name        = "${local.name_prefix}-pg15"
  family      = "postgres15"
  description = "pgvector + 성능 튜닝"

  parameter {
    name  = "shared_preload_libraries"
    value = "pg_stat_statements,pgvector"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  tags = merge(local.common_tags, { Name = "${local.name_prefix}-pg15-params" })
}

resource "aws_db_instance" "main" {
  identifier        = "${local.name_prefix}-rds"
  engine            = "postgres"
  engine_version    = "15.6"
  instance_class    = var.rds_instance_class
  allocated_storage = var.rds_allocated_storage
  storage_type      = "gp3"
  storage_encrypted = true
  kms_key_id        = aws_kms_key.rds.arn

  db_name  = var.db_name
  username = var.db_username
  password = var.db_password

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  parameter_group_name   = aws_db_parameter_group.postgres.name

  multi_az                = var.rds_multi_az # true 필수 — false 금지
  backup_retention_period = 7
  backup_window           = "03:00-04:00"
  maintenance_window      = "sun:04:00-sun:05:00"

  skip_final_snapshot       = false
  final_snapshot_identifier = "${local.name_prefix}-final-snapshot"

  deletion_protection = true

  tags = merge(local.common_tags, { Name = "${local.name_prefix}-rds" })
}
