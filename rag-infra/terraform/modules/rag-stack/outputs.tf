output "vpc_id" {
  description = "VPC ID"
  value       = aws_vpc.main.id
}

output "alb_dns_name" {
  description = "ALB DNS 이름"
  value       = aws_lb.main.dns_name
}

output "alb_zone_id" {
  description = "ALB Route53 Zone ID"
  value       = aws_lb.main.zone_id
}

output "rds_endpoint" {
  description = "RDS PostgreSQL 엔드포인트"
  value       = aws_db_instance.main.address
  sensitive   = true
}

output "rds_port" {
  description = "RDS PostgreSQL 포트"
  value       = aws_db_instance.main.port
}

output "db_secret_arn" {
  description = "DB 접속 정보 Secrets Manager ARN"
  value       = aws_secretsmanager_secret.db_password.arn
}

output "api_key_secret_arn" {
  description = "RAG Backend API Key Secrets Manager ARN"
  value       = aws_secretsmanager_secret.api_key.arn
}

output "jenkins_role_arn" {
  description = "Jenkins Cross-Account Deploy Role ARN"
  value       = aws_iam_role.jenkins_deploy.arn
}

output "kms_app_arn" {
  description = "App 데이터 암호화 KMS Key ARN"
  value       = aws_kms_key.app.arn
}

output "kms_rds_arn" {
  description = "RDS 암호화 KMS Key ARN"
  value       = aws_kms_key.rds.arn
}

output "kms_ec2_arn" {
  description = "EC2 EBS 암호화 KMS Key ARN"
  value       = aws_kms_key.ec2.arn
}

output "customer_id" {
  description = "고객사 ID"
  value       = var.customer_id
}

output "app_instance_ids" {
  description = "App EC2 인스턴스 ID 목록"
  value       = aws_instance.app[*].id
}

output "subnet_public_az_a_id" {
  description = "Public Subnet AZ-a ID"
  value       = aws_subnet.public_az_a.id
}

output "subnet_public_az_c_id" {
  description = "Public Subnet AZ-c ID"
  value       = aws_subnet.public_az_c.id
}

output "subnet_private_app_az_a_id" {
  description = "Private App Subnet AZ-a ID"
  value       = aws_subnet.private_app_az_a.id
}

output "subnet_private_data_az_a_id" {
  description = "Private Data Subnet AZ-a ID"
  value       = aws_subnet.private_data_az_a.id
}

output "acm_certificate_arn" {
  description = "ACM 인증서 ARN (per-customer 생성)"
  value       = aws_acm_certificate_validation.main.certificate_arn
}
