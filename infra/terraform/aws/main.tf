data "aws_region" "current" {}

resource "aws_ecs_cluster" "this" {
  name = "${var.name_prefix}-cluster"
}

resource "aws_security_group" "services" {
  name        = "${var.name_prefix}-services"
  description = "ECS service-to-service access"
  vpc_id      = var.vpc_id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "data" {
  name        = "${var.name_prefix}-data"
  description = "Data plane access for RDS and Amazon MQ"
  vpc_id      = var.vpc_id

  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.services.id]
  }

  ingress {
    from_port       = 5671
    to_port         = 5671
    protocol        = "tcp"
    security_groups = [aws_security_group.services.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_db_subnet_group" "this" {
  name       = "${var.name_prefix}-db"
  subnet_ids = var.private_subnet_ids
}

resource "aws_db_parameter_group" "postgres" {
  name   = "${var.name_prefix}-postgres-logical"
  family = "postgres16"

  parameter {
    name  = "rds.logical_replication"
    value = "1"
  }
}

resource "aws_db_instance" "postgres" {
  identifier             = "${var.name_prefix}-postgres"
  engine                 = "postgres"
  engine_version         = "16"
  instance_class         = "db.t4g.micro"
  allocated_storage      = 20
  db_name                = "task_engine"
  username               = var.postgres_username
  password               = var.postgres_password
  db_subnet_group_name   = aws_db_subnet_group.this.name
  parameter_group_name   = aws_db_parameter_group.postgres.name
  vpc_security_group_ids = [aws_security_group.data.id]
  skip_final_snapshot    = true
}

resource "aws_mq_broker" "rabbitmq" {
  broker_name        = "${var.name_prefix}-rabbitmq"
  engine_type        = "RabbitMQ"
  engine_version     = "3.13"
  host_instance_type = "mq.t3.micro"
  security_groups    = [aws_security_group.data.id]
  subnet_ids         = [var.private_subnet_ids[0]]

  user {
    username = var.rabbitmq_username
    password = var.rabbitmq_password
  }
}

resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/${var.name_prefix}/api"
  retention_in_days = 14
}

resource "aws_cloudwatch_log_group" "worker" {
  name              = "/ecs/${var.name_prefix}/worker"
  retention_in_days = 14
}

resource "aws_cloudwatch_log_group" "debezium" {
  name              = "/ecs/${var.name_prefix}/debezium"
  retention_in_days = 14
}

resource "aws_iam_role" "task_execution" {
  name = "${var.name_prefix}-task-execution"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "ecs-tasks.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "task_execution" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

locals {
  rabbitmq_host = replace(replace(aws_mq_broker.rabbitmq.instances[0].endpoints[0], "amqps://", ""), ":5671", "")
  db_env = [
    { name = "DB_HOST", value = aws_db_instance.postgres.address },
    { name = "DB_NAME", value = "task_engine" },
    { name = "DB_USER", value = var.postgres_username },
    { name = "DB_PASSWORD", value = var.postgres_password }
  ]
  mq_env = [
    { name = "MQ_HOST", value = local.rabbitmq_host },
    { name = "MQ_PORT", value = "5671" },
    { name = "MQ_USER", value = var.rabbitmq_username },
    { name = "MQ_PASSWORD", value = var.rabbitmq_password }
  ]
  api_mq_env = concat(local.mq_env, [
    { name = "MQ_SSL_ENABLED", value = "true" }
  ])
  worker_mq_env = concat(local.mq_env, [
    { name = "MQ_SCHEME", value = "amqps" }
  ]
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${var.name_prefix}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.task_execution.arn

  container_definitions = jsonencode([{
    name      = "api-gateway"
    image     = var.api_image
    essential = true
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    environment = concat(local.db_env, local.api_mq_env)
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.api.name
        awslogs-region        = data.aws_region.current.name
        awslogs-stream-prefix = "api"
      }
    }
  }])
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${var.name_prefix}-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.task_execution.arn

  container_definitions = jsonencode([{
    name      = "worker-node"
    image     = var.worker_image
    essential = true
    portMappings = [{ containerPort = 8090, protocol = "tcp" }]
    environment = local.worker_mq_env
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.worker.name
        awslogs-region        = data.aws_region.current.name
        awslogs-stream-prefix = "worker"
      }
    }
  }])
}

resource "aws_ecs_service" "api" {
  name            = "${var.name_prefix}-api"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.desired_api_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = var.private_subnet_ids
    security_groups = [aws_security_group.services.id]
  }
}

resource "aws_ecs_service" "worker" {
  name            = "${var.name_prefix}-worker"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = var.desired_worker_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = var.private_subnet_ids
    security_groups = [aws_security_group.services.id]
  }
}
