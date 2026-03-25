# Terraform Configuration for Greennode Task Engine

This Terraform configuration provisions the AWS infrastructure required to run the Greennode Task Engine distributed system on AWS EC2 instances.

## Architecture Overview

This configuration creates:

1. **Infrastructure EC2 Instance** (`t3.large`)
   - Hosts the complete distributed system stack:
     - PostgreSQL (Database)
     - RabbitMQ (Message Broker)
     - Debezium Server (CDC)
     - API Gateway (Spring Boot)
     - Worker Nodes (Go)
   - Pre-configured with Docker and Docker Compose
   - Exposed ports: 8080 (API), 15672 (RabbitMQ Management)

2. **Load Test EC2 Instance** (`t3.medium`)
   - Dedicated instance for running k6 load tests
   - Pre-configured with Docker and k6
   - Includes helper scripts for easy test execution

## Prerequisites

1. **AWS Account** with appropriate permissions to create:
   - VPC, Subnets, Internet Gateway, Route Tables
   - EC2 instances, Security Groups, Elastic IPs
   - IAM Roles and Instance Profiles

2. **AWS CLI** configured with credentials:

   ```bash
   aws configure
   ```

3. **Terraform** installed (version >= 1.0):

   ```bash
   terraform --version
   ```

4. **EC2 Key Pair** created in AWS Console:
   - Go to EC2 → Key Pairs → Create Key Pair
   - Download the `.pem` file to `~/.ssh/`
   - Set permissions: `chmod 400 ~/.ssh/your-key.pem`

## Quick Start

### 1. Configure Variables

Copy the example variables file and customize it:

```bash
cd terraform
cp terraform.tfvars.example terraform.tfvars
```

Edit `terraform.tfvars` and set:

- `key_pair_name`: Name of your EC2 key pair
- `allowed_ssh_cidr`: Your IP address (for better security)

### 2. Initialize Terraform

```bash
terraform init
```

### 3. Review the Plan

```bash
terraform plan
```

### 4. Apply the Configuration

```bash
terraform apply
```

Type `yes` when prompted to confirm.

### 5. Get the Outputs

After successful deployment, Terraform will output important information:

```bash
terraform output
```

Save these values:

- `infrastructure_public_ip`: IP to access the API and RabbitMQ
- `loadtest_public_ip`: IP to run load tests from
- `infrastructure_ssh_command`: Command to SSH into the infrastructure instance
- `loadtest_ssh_command`: Command to SSH into the load test instance

## Deploying the Application

### On the Infrastructure Instance

1. SSH into the infrastructure instance:

   ```bash
   ssh -i ~/.ssh/your-key.pem ec2-user@<infrastructure_public_ip>
   ```

2. Clone the repository:

   ```bash
   cd /home/ec2-user
   git clone <your-repo-url> greennode-task-engine
   cd greennode-task-engine
   ```

3. Start the services:

   ```bash
   docker-compose -f compose.yaml up -d
   ```

4. Check the status:

   ```bash
   docker-compose ps
   docker-compose logs -f
   ```

5. Test the API:

   ```bash
   curl -X POST http://localhost:8080/api/v1/jobs \
     -H "Content-Type: application/json" \
     -d '{"taskType":"matrix_multiplication","complexity":5}'
   ```

### On the Load Test Instance

1. SSH into the load test instance:

   ```bash
   ssh -i ~/.ssh/your-key.pem ec2-user@<loadtest_public_ip>
   ```

2. Copy the k6 test script:

   ```bash
   cd /home/ec2-user/load-tests
   git clone <your-repo-url> temp
   cp temp/infra/load-tests/k6-script.js ./
   rm -rf temp
   ```

3. Run the load test:

   ```bash
   ./run-loadtest.sh
   ```

## Configuration Details

### Instance Sizing

- **Infrastructure Instance** (`t3.large`):
  - 2 vCPUs, 8 GB RAM
  - 30 GB GP3 EBS volume
  - Suitable for running all services with moderate load

- **Load Test Instance** (`t3.medium`):
  - 2 vCPU, 4 GB RAM
  - 30 GB GP3 EBS volume
  - Sufficient for k6 load testing

### Security Groups

**Infrastructure Security Group**:

- SSH (22): From `allowed_ssh_cidr`
- API Gateway (8080): From anywhere (0.0.0.0/0)
- RabbitMQ Management (15672): From `allowed_ssh_cidr`
- RabbitMQ AMQP (5672): From VPC CIDR
- PostgreSQL (5432): From load test instance only

**Load Test Security Group**:

- SSH (22): From `allowed_ssh_cidr`
- All outbound traffic allowed

### Network Architecture

```text
Internet
    |
    v
Internet Gateway
    |
    v
Public Subnet (10.0.1.0/24)
    |
    +-- Infrastructure Instance (10.0.1.x)
    |   - API Gateway :8080
    |   - RabbitMQ :5672, :15672
    |   - PostgreSQL :5432
    |   - Debezium
    |   - Worker Nodes
    |
    +-- Load Test Instance (10.0.1.y)
        - k6 Load Testing
```

## Cost Estimation

Approximate monthly costs (us-east-2, March 2026):

- t3.large instance: ~$60/month (on-demand)
- t3.medium instance: ~$30/month (on-demand)
- 2 Elastic IPs: ~$7/month (if instances are running)
- EBS volumes: ~$5/month
- **Total: ~$102/month**

💡 **Cost Savings**:

- Stop instances when not in use to avoid compute charges
- Consider using Reserved Instances for long-term usage (up to 72% savings)
- Use Spot Instances for the load test instance (up to 90% savings)

## Accessing Services

After deployment:

- **API Gateway**: `http://<infrastructure_public_ip>:8080`
- **RabbitMQ Management**: `http://<infrastructure_public_ip>:15672`
  - Username: `green_user`
  - Password: `green_password`

## Monitoring and Troubleshooting

### Check Instance Status

```bash
# View user data logs
ssh ec2-user@<instance-ip>
sudo tail -f /var/log/user-data.log

# Check Docker status
docker ps
docker-compose ps

# View application logs
docker-compose logs -f api-gateway
docker-compose logs -f worker-node
```

### Common Issues

1. **Cannot SSH**: Check security group rules and ensure your IP is in `allowed_ssh_cidr`
2. **Services not starting**: Check user data logs at `/var/log/user-data.log`
3. **Out of memory**: Consider upgrading to `t3.xlarge` for infrastructure instance
4. **Network issues**: Verify security group rules and VPC configuration

## Cleanup

To destroy all resources and avoid ongoing charges:

```bash
terraform destroy
```

Type `yes` when prompted to confirm.

**Warning**: This will permanently delete all resources, including:

- EC2 instances and their data
- Elastic IPs
- Security groups
- VPC and networking components

## Variables Reference

| Variable                       | Description                       | Default                 | Required |
| ------------------------------ | --------------------------------- | ----------------------- | -------- |
| `aws_region`                   | AWS region                        | `us-east-2`             | No       |
| `project_name`                 | Project name for resource tagging | `greennode-task-engine` | No       |
| `environment`                  | Environment name                  | `dev`                   | No       |
| `vpc_cidr`                     | VPC CIDR block                    | `10.0.0.0/16`           | No       |
| `public_subnet_cidr`           | Public subnet CIDR                | `10.0.1.0/24`           | No       |
| `infrastructure_instance_type` | Infrastructure EC2 type           | `t3.large`              | No       |
| `loadtest_instance_type`       | Load test EC2 type                | `t3.medium`             | No       |
| `key_pair_name`                | EC2 key pair name                 | -                       | **Yes**  |
| `allowed_ssh_cidr`             | CIDR blocks for SSH access        | `["0.0.0.0/0"]`         | No       |

## Security Best Practices

1. **Restrict SSH Access**: Update `allowed_ssh_cidr` to your IP address only
2. **Use Secrets Manager**: Store database credentials in AWS Secrets Manager
3. **Enable Encryption**: EBS volumes are encrypted by default
4. **Regular Updates**: Run `dnf update` regularly on instances
5. **IAM Roles**: Instances use IAM roles instead of access keys
6. **Network Isolation**: PostgreSQL only accessible from load test instance
7. **HTTPS**: Consider adding an Application Load Balancer with SSL/TLS

## Next Steps

- [ ] Set up CloudWatch monitoring and alarms
- [ ] Configure automated backups for PostgreSQL data
- [ ] Implement CI/CD pipeline for automated deployments
- [ ] Add Application Load Balancer for high availability
- [ ] Set up VPN or AWS Systems Manager Session Manager for secure access
- [ ] Configure CloudWatch Logs for centralized logging

## Support

For issues or questions:

1. Check the setup instructions on each instance: `/home/ec2-user/SETUP_INSTRUCTIONS.txt`
2. Review the main project documentation
3. Check Terraform documentation: <https://registry.terraform.io/providers/hashicorp/aws/latest/docs>
