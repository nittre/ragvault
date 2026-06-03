# 보안 그룹 정의
# 원칙: SSH(22) 없음 — SSM Session Manager 로만 접근
# 원칙: IMDSv2 전용 (ec2.tf 의 metadata_options 와 연계)

# ALB 보안 그룹 — 인터넷 80/443 허용
# 순환 참조 방지: alb↔app 교차 규칙은 aws_security_group_rule 로 분리
resource "aws_security_group" "alb" {
  name        = "${local.name_prefix}-alb"
  vpc_id      = aws_vpc.main.id
  description = "ALB: internet 80/443 inbound, App subnet 8080 outbound"

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "HTTP (301 redirect)"
  }

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "HTTPS"
  }

  tags = merge(local.common_tags, { Name = "${local.name_prefix}-sg-alb" })

  lifecycle {
    create_before_destroy = true
  }
}

# ALB → App 8080 egress (순환 방지용 분리)
resource "aws_security_group_rule" "alb_to_app" {
  type                     = "egress"
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  security_group_id        = aws_security_group.alb.id
  source_security_group_id = aws_security_group.app.id
  description              = "ALB to App 8080"
}

# App 서버 보안 그룹 (k3s 노드)
resource "aws_security_group" "app" {
  name        = "${local.name_prefix}-app"
  vpc_id      = aws_vpc.main.id
  description = "App node: ALB 8080 inbound, k3s internal, GPU/RDS outbound"

  # k3s API 서버 — 노드 간 통신
  ingress {
    from_port   = 6443
    to_port     = 6443
    protocol    = "tcp"
    self        = true
    description = "k3s API server (node-to-node)"
  }

  # k3s Flannel VXLAN
  ingress {
    from_port   = 8472
    to_port     = 8472
    protocol    = "udp"
    self        = true
    description = "k3s Flannel VXLAN (node-to-node)"
  }

  # k3s kubelet metrics
  ingress {
    from_port   = 10250
    to_port     = 10250
    protocol    = "tcp"
    self        = true
    description = "k3s kubelet (node-to-node)"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
    description = "App outbound all (via NAT GW)"
  }

  tags = merge(local.common_tags, { Name = "${local.name_prefix}-sg-app" })

  lifecycle {
    create_before_destroy = true
  }
}

# ALB → App 8080 ingress (순환 방지용 분리)
resource "aws_security_group_rule" "app_from_alb" {
  type                     = "ingress"
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  security_group_id        = aws_security_group.app.id
  source_security_group_id = aws_security_group.alb.id
  description              = "ALB to App"
}

# GPU 보안 그룹 (Ollama)
resource "aws_security_group" "gpu" {
  name        = "${local.name_prefix}-gpu"
  vpc_id      = aws_vpc.main.id
  description = "GPU node: Ollama 11434 from App only, no SSH"

  ingress {
    from_port       = 11434
    to_port         = 11434
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
    description     = "App to Ollama API"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
    description = "GPU outbound all (model downloads etc)"
  }

  tags = merge(local.common_tags, { Name = "${local.name_prefix}-sg-gpu" })

  lifecycle {
    create_before_destroy = true
  }
}

# RDS 보안 그룹
resource "aws_security_group" "rds" {
  name        = "${local.name_prefix}-rds"
  vpc_id      = aws_vpc.main.id
  description = "RDS PostgreSQL: port 5432 from App only"

  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
    description     = "App to PostgreSQL"
  }

  egress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
    description     = "RDS to App (response)"
  }

  tags = merge(local.common_tags, { Name = "${local.name_prefix}-sg-rds" })

  lifecycle {
    create_before_destroy = true
  }
}
