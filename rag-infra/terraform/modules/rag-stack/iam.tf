# IAM — Cross-Account Role + EC2 Instance Profile
# 원칙: 최소 권한 (AdministratorAccess 금지)
# 원칙: Jenkins Cross-Account는 ExternalId 필수
# 원칙: EC2 접근은 SSM Session Manager 전용 (SSH 없음)

# ── App 노드 IAM ──────────────────────────────────────────────────────────────

resource "aws_iam_role" "app" {
  name = "${local.name_prefix}-app-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = local.common_tags
}

# SSM Session Manager (SSH 대체)
resource "aws_iam_role_policy_attachment" "app_ssm" {
  role       = aws_iam_role.app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# App 노드 전용 권한 — Secrets Manager, KMS, S3, ECR
resource "aws_iam_role_policy" "app_secrets" {
  name = "${local.name_prefix}-app-policy"
  role = aws_iam_role.app.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "SecretsRead"
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue",
          "secretsmanager:DescribeSecret"
        ]
        Resource = [
          "arn:aws:secretsmanager:${var.aws_region}:${var.customer_account_id}:secret:${local.name_prefix}-*"
        ]
      },
      {
        Sid    = "KMSDecrypt"
        Effect = "Allow"
        Action = [
          "kms:Decrypt",
          "kms:GenerateDataKey"
        ]
        Resource = [aws_kms_key.app.arn]
      },
      {
        Sid    = "S3FilesBucket"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:ListBucket"
        ]
        Resource = [
          "arn:aws:s3:::${local.name_prefix}-files",
          "arn:aws:s3:::${local.name_prefix}-files/*"
        ]
      },
      {
        Sid    = "ECRPull"
        Effect = "Allow"
        Action = [
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage",
          "ecr:GetAuthorizationToken"
        ]
        Resource = "*"
      }
    ]
  })
}

resource "aws_iam_instance_profile" "app" {
  name = "${local.name_prefix}-app-profile"
  role = aws_iam_role.app.name
}

# ── GPU 노드 IAM ──────────────────────────────────────────────────────────────

resource "aws_iam_role" "gpu" {
  name = "${local.name_prefix}-gpu-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = local.common_tags
}

# SSM Session Manager (SSH 대체)
resource "aws_iam_role_policy_attachment" "gpu_ssm" {
  role       = aws_iam_role.gpu.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# GPU 노드 — ECR Pull (모델 업데이트용 컨테이너 이미지)
resource "aws_iam_role_policy" "gpu_ecr" {
  name = "${local.name_prefix}-gpu-ecr-policy"
  role = aws_iam_role.gpu.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ECRPull"
        Effect = "Allow"
        Action = [
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage",
          "ecr:GetAuthorizationToken"
        ]
        Resource = "*"
      }
    ]
  })
}

resource "aws_iam_instance_profile" "gpu" {
  name = "${local.name_prefix}-gpu-profile"
  role = aws_iam_role.gpu.name
}

# ── Jenkins Cross-Account Role ────────────────────────────────────────────────
# 고객사 계정에 생성 — Jenkins(회사 계정) AssumeRole
# ExternalId 필수 (Confused Deputy 방지)

resource "aws_iam_role" "jenkins_deploy" {
  name = "${local.name_prefix}-jenkins-deploy"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        AWS = "arn:aws:iam::${var.jenkins_account_id}:root"
      }
      Action = "sts:AssumeRole"
      Condition = {
        StringEquals = {
          "sts:ExternalId" = "${local.name_prefix}-jenkins"
        }
      }
    }]
  })

  tags = local.common_tags
}

resource "aws_iam_role_policy" "jenkins_deploy" {
  name = "${local.name_prefix}-jenkins-deploy-policy"
  role = aws_iam_role.jenkins_deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ECRPush"
        Effect = "Allow"
        Action = [
          "ecr:GetAuthorizationToken",
          "ecr:BatchCheckLayerAvailability",
          "ecr:PutImage",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          "ecr:DescribeRepositories",
          "ecr:CreateRepository"
        ]
        Resource = "*"
      },
      {
        Sid    = "SSMRunCommand"
        Effect = "Allow"
        Action = [
          "ssm:SendCommand",
          "ssm:GetCommandInvocation",
          "ssm:ListCommandInvocations"
        ]
        Resource = [
          "arn:aws:ssm:${var.aws_region}:*:document/AWS-RunShellScript",
          "arn:aws:ec2:${var.aws_region}:${var.customer_account_id}:instance/*"
        ]
        Condition = {
          StringEquals = {
            "ssm:ResourceTag/Project" = "rag-saas"
          }
        }
      },
      {
        Sid    = "HelmDeployDescribe"
        Effect = "Allow"
        Action = [
          "ec2:DescribeInstances",
          "ec2:DescribeInstanceStatus"
        ]
        Resource = "*"
        Condition = {
          StringEquals = {
            "ec2:ResourceTag/Project" = "rag-saas"
          }
        }
      }
    ]
  })
}
