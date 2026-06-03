variable "customer_id" {
  description = "고객사 ID"
  type        = string
}

variable "customer_account_id" {
  description = "고객사 AWS 계정 ID"
  type        = string
}

variable "aws_region" {
  description = "배포 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "environment" {
  description = "환경"
  type        = string
  default     = "prod"
}

variable "vpc_cidr" {
  type    = string
  default = "10.0.0.0/16"
}

variable "ec2_app_instance_type" {
  type    = string
  default = "t3.medium"
}

variable "ec2_gpu_instance_type" {
  type    = string
  default = "g5.xlarge"
}

variable "ec2_app_count" {
  type    = number
  default = 2
}

variable "rds_instance_class" {
  type    = string
  default = "db.t3.medium"
}

variable "rds_allocated_storage" {
  type    = number
  default = 50
}

variable "db_name" {
  type    = string
  default = "ragdb"
}

variable "db_username" {
  type    = string
  default = "raguser"
}

variable "db_password" {
  type      = string
  sensitive = true
}

variable "ses_sender_domain" {
  type = string
}

variable "route53_zone_id" {
  type = string
}

variable "jenkins_account_id" {
  type = string
}
