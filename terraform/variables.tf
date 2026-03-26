variable "aws_region" {
  description = "AWS region for resources"
  type        = string
  default     = "us-east-2"
}

variable "project_name" {
  description = "Project name used for resource naming"
  type        = string
  default     = "greennode-task-engine"
}

variable "environment" {
  description = "Environment name (e.g., dev, staging, prod)"
  type        = string
  default     = "dev"
}

variable "vpc_cidr" {
  description = "CIDR block for VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "public_subnet_cidr" {
  description = "CIDR block for public subnet"
  type        = string
  default     = "10.0.1.0/24"
}

variable "infrastructure_instance_type" {
  description = "EC2 instance type for infrastructure server"
  type        = string
  default     = "t3.xlarge" # 4 vCPUs, 16 GB RAM - suitable for running multiple containers
}

variable "loadtest_instance_type" {
  description = "EC2 instance type for load test server"
  type        = string
  default     = "t3.medium" # 2 vCPUs, 4 GB RAM - suitable for k6 load testing
}

variable "key_pair_name" {
  description = "Name of the AWS key pair to use for SSH access"
  type        = string

  validation {
    condition     = length(var.key_pair_name) > 0
    error_message = "Key pair name must be provided for SSH access."
  }
}

variable "allowed_ssh_cidr" {
  description = "List of CIDR blocks allowed to SSH into the instances"
  type        = list(string)
  default     = ["0.0.0.0/0"] # IMPORTANT: Restrict this to your IP in production!
}