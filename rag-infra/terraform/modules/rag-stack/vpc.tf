# VPC 및 서브넷 구성
# ADR-0003: ALB Multi-AZ (AZ-a + AZ-c), 컴퓨트 Single AZ (AZ-a)
#
# 네트워크 레이아웃:
#   Public Subnet AZ-a  (10.0.1.0/24)  — ALB, NAT GW
#   Public Subnet AZ-c  (10.0.2.0/24)  — ALB (Multi-AZ 요구사항)
#   Private App AZ-a    (10.0.10.0/24) — k3s App 노드
#   Private App AZ-c    (10.0.11.0/24) — k3s AZ-c reserve (향후 확장)
#   Private LLM AZ-a    (10.0.20.0/24) — GPU 노드 (Ollama)
#   Private Data AZ-a   (10.0.30.0/24) — RDS primary
#   Private Data AZ-c   (10.0.31.0/24) — RDS standby (Multi-AZ)
#
# NAT Gateway: AZ-a 1개만 (비용 절감)

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags = merge(local.common_tags, { Name = "${local.name_prefix}-vpc" })
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = merge(local.common_tags, { Name = "${local.name_prefix}-igw" })
}

# Public Subnets — ALB Multi-AZ 의무 (ADR-0003)
resource "aws_subnet" "public_az_a" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, 1)
  availability_zone       = "${var.aws_region}a"
  map_public_ip_on_launch = true
  tags = merge(local.common_tags, { Name = "${local.name_prefix}-public-az-a", Tier = "public" })
}

resource "aws_subnet" "public_az_c" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, 2)
  availability_zone       = "${var.aws_region}c"
  map_public_ip_on_launch = true
  tags = merge(local.common_tags, { Name = "${local.name_prefix}-public-az-c", Tier = "public" })
}

# Private App Subnets — k3s 노드
resource "aws_subnet" "private_app_az_a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, 10)
  availability_zone = "${var.aws_region}a"
  tags = merge(local.common_tags, { Name = "${local.name_prefix}-private-app-az-a", Tier = "app" })
}

resource "aws_subnet" "private_app_az_c" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, 11)
  availability_zone = "${var.aws_region}c"
  tags = merge(local.common_tags, { Name = "${local.name_prefix}-private-app-az-c", Tier = "app" })
}

# Private LLM Subnet — GPU (Ollama), Single AZ (비용 절감)
resource "aws_subnet" "private_llm_az_a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, 20)
  availability_zone = "${var.aws_region}a"
  tags = merge(local.common_tags, { Name = "${local.name_prefix}-private-llm-az-a", Tier = "llm" })
}

# Private Data Subnets — RDS Multi-AZ
resource "aws_subnet" "private_data_az_a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, 30)
  availability_zone = "${var.aws_region}a"
  tags = merge(local.common_tags, { Name = "${local.name_prefix}-private-data-az-a", Tier = "data" })
}

resource "aws_subnet" "private_data_az_c" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, 31)
  availability_zone = "${var.aws_region}c"
  tags = merge(local.common_tags, { Name = "${local.name_prefix}-private-data-az-c", Tier = "data" })
}

# Elastic IP for NAT Gateway
resource "aws_eip" "nat" {
  domain     = "vpc"
  depends_on = [aws_internet_gateway.main]
  tags       = merge(local.common_tags, { Name = "${local.name_prefix}-nat-eip" })
}

# NAT Gateway — AZ-a 1개 (비용 절감)
resource "aws_nat_gateway" "az_a" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public_az_a.id
  depends_on    = [aws_internet_gateway.main]
  tags          = merge(local.common_tags, { Name = "${local.name_prefix}-nat-az-a" })
}

# Route Tables
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
  tags = merge(local.common_tags, { Name = "${local.name_prefix}-rt-public" })
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.az_a.id
  }
  tags = merge(local.common_tags, { Name = "${local.name_prefix}-rt-private" })
}

# Route Table Associations
resource "aws_route_table_association" "public_az_a" {
  subnet_id      = aws_subnet.public_az_a.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "public_az_c" {
  subnet_id      = aws_subnet.public_az_c.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "private_app_az_a" {
  subnet_id      = aws_subnet.private_app_az_a.id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "private_app_az_c" {
  subnet_id      = aws_subnet.private_app_az_c.id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "private_llm_az_a" {
  subnet_id      = aws_subnet.private_llm_az_a.id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "private_data_az_a" {
  subnet_id      = aws_subnet.private_data_az_a.id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "private_data_az_c" {
  subnet_id      = aws_subnet.private_data_az_c.id
  route_table_id = aws_route_table.private.id
}
