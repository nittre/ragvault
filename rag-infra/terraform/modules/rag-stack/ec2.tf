# EC2 인스턴스 — k3s App 노드 + GPU(Ollama) 노드
#
# App 노드 (k3s):
#   - t3.medium × 2 (Private App Subnet AZ-a, 컴퓨트 Single AZ — ADR-0003)
#   - IMDSv2 전용 (http_tokens = required)
#   - SSM Session Manager 접근 (SSH 없음)
#
# GPU 노드 (Ollama):
#   - g5.xlarge Spot (Private LLM Subnet AZ-a)
#   - AMI: qwen2.5:14b + qwen2.5-vl:7b + nomic-embed-text 사전 설치 (packer 빌드)
#   - Spot 인터럽션 핸들러: k3s drain 실행

# Amazon Linux 2023 최신 AMI
data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# k3s App 노드 × 2
resource "aws_instance" "app" {
  count         = var.ec2_app_count
  ami           = data.aws_ami.al2023.id
  instance_type = var.ec2_app_instance_type
  subnet_id     = aws_subnet.private_app_az_a.id

  iam_instance_profile   = aws_iam_instance_profile.app.name
  vpc_security_group_ids = [aws_security_group.app.id]

  root_block_device {
    volume_size = 30
    volume_type = "gp3"
    encrypted   = true
    kms_key_id  = aws_kms_key.ec2.arn
  }

  # IMDSv2 전용 — 보안 원칙
  metadata_options {
    http_tokens   = "required"
    http_endpoint = "enabled"
  }

  user_data = base64encode(<<-EOF
    #!/bin/bash
    set -ex

    # SSM Agent (먼저 설치 — 접근 수단 확보)
    yum install -y amazon-ssm-agent
    systemctl enable amazon-ssm-agent
    systemctl start amazon-ssm-agent

    # k3s 설치 (traefik 비활성화 — Helm ingress-nginx 사용)
    curl -sfL https://get.k3s.io | sh -s - \
      --disable=traefik \
      --node-label "role=app"
    systemctl enable k3s
    systemctl start k3s
  EOF
  )

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-app-${count.index + 1}"
    Role = "k3s-app"
  })
}

# GPU Spot 인스턴스 (Ollama)
resource "aws_spot_instance_request" "gpu" {
  ami           = data.aws_ami.al2023.id
  instance_type = var.ec2_gpu_instance_type
  spot_type     = "persistent"
  instance_interruption_behavior = "stop"
  subnet_id              = aws_subnet.private_llm_az_a.id

  iam_instance_profile   = aws_iam_instance_profile.gpu.name
  vpc_security_group_ids = [aws_security_group.gpu.id]

  root_block_device {
    volume_size = 100 # 모델 저장용 (qwen2.5:14b + qwen2.5-vl:7b + nomic-embed-text)
    volume_type = "gp3"
    encrypted   = true
    kms_key_id  = aws_kms_key.ec2.arn
  }

  # IMDSv2 전용 — 보안 원칙
  metadata_options {
    http_tokens   = "required"
    http_endpoint = "enabled"
  }

  user_data = base64encode(<<-EOF
    #!/bin/bash
    set -ex

    # SSM Agent (먼저 설치 — 접근 수단 확보)
    yum install -y amazon-ssm-agent
    systemctl enable amazon-ssm-agent
    systemctl start amazon-ssm-agent

    # Ollama 설치
    curl -fsSL https://ollama.com/install.sh | sh
    systemctl enable ollama
    systemctl start ollama

    # 모델 사전 pull (AMI 빌드 시 수행, 여기서는 fallback)
    # 정상 운영: packer/ollama-ami.pkr.hcl 으로 빌드한 AMI 사용
    sleep 10
    ollama pull qwen2.5:14b-instruct-q4_K_M || true
    ollama pull qwen2.5-vl:7b-instruct-q4_K_M || true
    ollama pull nomic-embed-text || true

    # Spot 인터럽션 핸들러 — k3s drain 실행
    cat > /usr/local/bin/spot-termination-handler.sh << 'HANDLER'
    #!/bin/bash
    # IMDSv2 토큰 획득
    TOKEN=$(curl -s -X PUT "http://169.254.169.254/latest/api/token" \
      -H "X-aws-ec2-metadata-token-ttl-seconds: 21600")
    while true; do
      TERMINATION=$(curl -s -o /dev/null -w "%%{http_code}" \
        -H "X-aws-ec2-metadata-token: $TOKEN" \
        "http://169.254.169.254/latest/meta-data/spot/termination-time" 2>/dev/null)
      if [ "$TERMINATION" = "200" ]; then
        logger "Spot interruption notice received — draining node"
        /usr/local/bin/k3s kubectl drain "$(hostname)" \
          --ignore-daemonsets \
          --delete-emptydir-data \
          --force \
          --timeout=90s || true
        break
      fi
      sleep 5
    done
    HANDLER
    chmod +x /usr/local/bin/spot-termination-handler.sh

    # systemd 서비스로 등록
    cat > /etc/systemd/system/spot-termination-handler.service << 'SERVICE'
    [Unit]
    Description=Spot Termination Handler
    After=network.target

    [Service]
    Type=simple
    ExecStart=/usr/local/bin/spot-termination-handler.sh
    Restart=always
    RestartSec=10

    [Install]
    WantedBy=multi-user.target
    SERVICE
    systemctl daemon-reload
    systemctl enable spot-termination-handler
    systemctl start spot-termination-handler
  EOF
  )

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-gpu-ollama"
    Role = "ollama-gpu"
  })
}

# ALB Target Group 등록
resource "aws_lb_target_group_attachment" "app" {
  count            = var.ec2_app_count
  target_group_arn = aws_lb_target_group.app.arn
  target_id        = aws_instance.app[count.index].id
  port             = 8080
}
