variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "name_prefix" {
  type    = string
  default = "task-engine"
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "api_image" {
  type = string
}

variable "worker_image" {
  type = string
}

variable "debezium_image" {
  type    = string
  default = "quay.io/debezium/server:3.4"
}

variable "postgres_username" {
  type    = string
  default = "green_user"
}

variable "postgres_password" {
  type      = string
  sensitive = true
}

variable "rabbitmq_username" {
  type    = string
  default = "green_user"
}

variable "rabbitmq_password" {
  type      = string
  sensitive = true
}

variable "desired_api_count" {
  type    = number
  default = 2
}

variable "desired_worker_count" {
  type    = number
  default = 2
}
