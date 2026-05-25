output "ecs_cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "postgres_endpoint" {
  value = aws_db_instance.postgres.address
}

output "rabbitmq_endpoint" {
  value = aws_mq_broker.rabbitmq.instances[0].endpoints[0]
}
