terraform {
  required_version = ">= 1.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "greennode-task-engine"
      ManagedBy   = "Terraform"
      Environment = var.environment
    }
  }
}

# Data source for latest Amazon Linux 2023 AMI
data "aws_ami" "amazon_linux_2023" {
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

# VPC Configuration
resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "${var.project_name}-vpc"
  }
}

# Internet Gateway
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-igw"
  }
}

# Public Subnet
resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = var.public_subnet_cidr
  availability_zone       = "${var.aws_region}a"
  map_public_ip_on_launch = true

  tags = {
    Name = "${var.project_name}-public-subnet"
  }
}

# Route Table
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "${var.project_name}-public-rt"
  }
}

# Route Table Association
resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# Security Group for Infrastructure Instance
resource "aws_security_group" "infrastructure" {
  name        = "${var.project_name}-infrastructure-sg"
  description = "Security group for infrastructure EC2 instance"
  vpc_id      = aws_vpc.main.id

  # SSH access
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = var.allowed_ssh_cidr
    description = "SSH access"
  }

  # API Gateway port
  ingress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "API Gateway HTTP"
  }

  #   # PostgreSQL (internal only, from load test instance)
  #   ingress {
  #     from_port       = 5432
  #     to_port         = 5432
  #     protocol        = "tcp"
  #     security_groups = [aws_security_group.loadtest.id]
  #     description     = "PostgreSQL access from load test instance"
  #   }

  # RabbitMQ Management UI
  ingress {
    from_port   = 15672
    to_port     = 15672
    protocol    = "tcp"
    cidr_blocks = var.allowed_ssh_cidr
    description = "RabbitMQ Management UI"
  }

  #   # RabbitMQ AMQP
  #   ingress {
  #     from_port   = 5672
  #     to_port     = 5672
  #     protocol    = "tcp"
  #     cidr_blocks = [var.vpc_cidr]
  #     description = "RabbitMQ AMQP"
  #   }

  # Jaeger UI
  ingress {
    from_port   = 16686
    to_port     = 16686
    protocol    = "tcp"
    cidr_blocks = var.allowed_ssh_cidr
    description = "Jaeger UI"
  }

  # Allow all outbound traffic
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
    description = "All outbound traffic"
  }

  tags = {
    Name = "${var.project_name}-infrastructure-sg"
  }
}

# Security Group for Load Test Instance
resource "aws_security_group" "loadtest" {
  name        = "${var.project_name}-loadtest-sg"
  description = "Security group for load test EC2 instance"
  vpc_id      = aws_vpc.main.id

  # SSH access
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = var.allowed_ssh_cidr
    description = "SSH access"
  }

  # Allow all outbound traffic
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
    description = "All outbound traffic"
  }

  tags = {
    Name = "${var.project_name}-loadtest-sg"
  }
}

# IAM Role for EC2 instances
resource "aws_iam_role" "ec2_role" {
  name = "${var.project_name}-ec2-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ec2.amazonaws.com"
        }
      }
    ]
  })

  tags = {
    Name = "${var.project_name}-ec2-role"
  }
}

# Attach CloudWatch policy for logging
resource "aws_iam_role_policy_attachment" "cloudwatch_logs" {
  role       = aws_iam_role.ec2_role.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
}

# IAM Instance Profile
resource "aws_iam_instance_profile" "ec2_profile" {
  name = "${var.project_name}-ec2-profile"
  role = aws_iam_role.ec2_role.name
}

# Infrastructure EC2 Instance
resource "aws_instance" "infrastructure" {
  ami                    = data.aws_ami.amazon_linux_2023.id
  instance_type          = var.infrastructure_instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.infrastructure.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2_profile.name
  key_name               = var.key_pair_name

  root_block_device {
    volume_size           = 30
    volume_type           = "gp3"
    delete_on_termination = true
    encrypted             = true
  }

  user_data = templatefile("${path.module}/user-data-infra.sh", {
    project_name = var.project_name
  })

  user_data_replace_on_change = true

  tags = {
    Name = "${var.project_name}-infrastructure"
    Role = "Infrastructure"
  }
}

# Load Test EC2 Instance
resource "aws_instance" "loadtest" {
  ami                    = data.aws_ami.amazon_linux_2023.id
  instance_type          = var.loadtest_instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.loadtest.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2_profile.name
  key_name               = var.key_pair_name

  root_block_device {
    volume_size           = 30
    volume_type           = "gp3"
    delete_on_termination = true
    encrypted             = true
  }

  user_data = templatefile("${path.module}/user-data-loadtest.sh", {
    infrastructure_private_ip = aws_instance.infrastructure.private_ip
  })

  user_data_replace_on_change = true

  depends_on = [aws_instance.infrastructure]

  tags = {
    Name = "${var.project_name}-loadtest"
    Role = "LoadTest"
  }
}

# Elastic IP for Infrastructure Instance (optional, but recommended for stable access)
resource "aws_eip" "infrastructure" {
  instance = aws_instance.infrastructure.id
  domain   = "vpc"

  tags = {
    Name = "${var.project_name}-infrastructure-eip"
  }

  depends_on = [aws_internet_gateway.main]
}

# Elastic IP for Load Test Instance
resource "aws_eip" "loadtest" {
  instance = aws_instance.loadtest.id
  domain   = "vpc"

  tags = {
    Name = "${var.project_name}-loadtest-eip"
  }

  depends_on = [aws_internet_gateway.main]
}
