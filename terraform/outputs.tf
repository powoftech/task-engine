output "infrastructure_public_ip" {
  description = "Public IP address of the infrastructure EC2 instance"
  value       = aws_eip.infrastructure.public_ip
}

output "infrastructure_private_ip" {
  description = "Private IP address of the infrastructure EC2 instance"
  value       = aws_instance.infrastructure.private_ip
}

output "loadtest_public_ip" {
  description = "Public IP address of the load test EC2 instance"
  value       = aws_eip.loadtest.public_ip
}

output "loadtest_private_ip" {
  description = "Private IP address of the load test EC2 instance"
  value       = aws_instance.loadtest.private_ip
}

output "infrastructure_ssh_command" {
  description = "SSH command to connect to the infrastructure instance"
  value       = "ssh -i ~/.ssh/${var.key_pair_name}.pem ec2-user@${aws_eip.infrastructure.public_ip}"
}

output "loadtest_ssh_command" {
  description = "SSH command to connect to the load test instance"
  value       = "ssh -i ~/.ssh/${var.key_pair_name}.pem ec2-user@${aws_eip.loadtest.public_ip}"
}

output "api_gateway_url" {
  description = "URL of the API Gateway"
  value       = "http://${aws_eip.infrastructure.public_ip}:8080"
}

output "rabbitmq_management_url" {
  description = "URL of the RabbitMQ Management UI"
  value       = "http://${aws_eip.infrastructure.public_ip}:15672"
}

output "vpc_id" {
  description = "ID of the VPC"
  value       = aws_vpc.main.id
}

output "subnet_id" {
  description = "ID of the public subnet"
  value       = aws_subnet.public.id
}

output "infrastructure_security_group_id" {
  description = "ID of the infrastructure security group"
  value       = aws_security_group.infrastructure.id
}

output "loadtest_security_group_id" {
  description = "ID of the load test security group"
  value       = aws_security_group.loadtest.id
}
