packer {
  required_plugins {
    amazon = {
      version = ">= 1.3.0"
      source  = "github.com/hashicorp/amazon"
    }
  }
}

variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "instance_type" {
  type        = string
  default     = "g5.xlarge"
  description = "GPU 인스턴스 타입 — 모델 pull 에 필요"
}

variable "ami_name_prefix" {
  type    = string
  default = "rag-ollama"
}

variable "subnet_id" {
  type        = string
  description = "빌드용 Public Subnet ID (임시 인스턴스)"
  default     = ""
}

source "amazon-ebs" "ollama" {
  region        = var.aws_region
  instance_type = var.instance_type

  source_ami_filter {
    filters = {
      name                = "al2023-ami-*-x86_64"
      virtualization-type = "hvm"
    }
    owners      = ["amazon"]
    most_recent = true
  }

  ami_name     = "${var.ami_name_prefix}-{{timestamp}}"
  ssh_username = "ec2-user"

  # IMDSv2 전용
  metadata_options {
    http_tokens                 = "required"
    http_endpoint               = "enabled"
    http_put_response_hop_limit = 1
  }

  tags = {
    Name    = var.ami_name_prefix
    Purpose = "Ollama + 모델 사전 빌드 AMI"
    Models  = "qwen2.5:14b-instruct-q4_K_M,qwen2.5-vl:7b-instruct-q4_K_M,nomic-embed-text"
    BuildAt = "{{timestamp}}"
  }

  launch_block_device_mappings {
    device_name           = "/dev/xvda"
    volume_size           = 100
    volume_type           = "gp3"
    delete_on_termination = true
    encrypted             = true
  }
}

build {
  name    = "rag-ollama-ami"
  sources = ["source.amazon-ebs.ollama"]

  provisioner "shell" {
    inline = [
      # 패키지 업데이트
      "sudo dnf update -y",

      # CUDA 드라이버 의존성
      "sudo dnf install -y dkms kernel-devel gcc make",

      # SSM Agent (접근 수단 확보)
      "sudo yum install -y amazon-ssm-agent",
      "sudo systemctl enable amazon-ssm-agent",

      # Ollama 설치
      "curl -fsSL https://ollama.com/install.sh | sudo sh",
      "sudo systemctl enable ollama",
      "sudo systemctl start ollama",

      # Ollama 준비 대기
      "sleep 15",
      "ollama --version",

      # 모델 사전 pull — ADR-0004: Q4_K_M 양자화
      "ollama pull qwen2.5:14b-instruct-q4_K_M",
      "ollama pull qwen2.5-vl:7b-instruct-q4_K_M",
      "ollama pull nomic-embed-text",

      # 모델 목록 검증
      "ollama list",
      "ollama list | grep -E 'qwen2.5|nomic' || (echo 'ERROR: 모델 pull 실패' && exit 1)",

      # Spot 인터럽션 핸들러 사전 배치
      "sudo tee /usr/local/bin/spot-termination-handler.sh << 'HANDLER'",
      "#!/bin/bash",
      "TOKEN=$(curl -s -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 21600')",
      "while true; do",
      "  STATUS=$(curl -s -o /dev/null -w '%{http_code}' -H \"X-aws-ec2-metadata-token: $TOKEN\" http://169.254.169.254/latest/meta-data/spot/termination-time 2>/dev/null)",
      "  if [ \"$STATUS\" = \"200\" ]; then",
      "    logger 'Spot interruption — draining node'",
      "    /usr/local/bin/k3s kubectl drain $(hostname) --ignore-daemonsets --delete-emptydir-data --force --timeout=90s || true",
      "    break",
      "  fi",
      "  sleep 5",
      "done",
      "HANDLER",
      "sudo chmod +x /usr/local/bin/spot-termination-handler.sh",

      # systemd 서비스 등록
      "sudo tee /etc/systemd/system/spot-termination-handler.service << 'SERVICE'",
      "[Unit]",
      "Description=Spot Termination Handler",
      "After=network.target",
      "",
      "[Service]",
      "Type=simple",
      "ExecStart=/usr/local/bin/spot-termination-handler.sh",
      "Restart=always",
      "RestartSec=10",
      "",
      "[Install]",
      "WantedBy=multi-user.target",
      "SERVICE",
      "sudo systemctl daemon-reload",
      "sudo systemctl enable spot-termination-handler",

      # 빌드 정보 기록
      "echo \"Built: $(date)\" | sudo tee /etc/rag-ami-info",
      "ollama list | sudo tee -a /etc/rag-ami-info",

      # 정리
      "sudo dnf clean all",
    ]
  }
}
