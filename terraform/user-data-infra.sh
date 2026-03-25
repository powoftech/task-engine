#!/bin/bash
set -e

# Logging function
log() {
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] $*" | tee -a /var/log/user-data.log
}

log "Starting infrastructure instance setup..."

# Update system packages
log "Updating system packages..."
dnf update -y

# Install Docker
log "Installing Docker..."
dnf install -y docker

# Start and enable Docker
log "Starting Docker service..."
systemctl start docker
systemctl enable docker

# Add ec2-user to docker group
usermod -aG docker ec2-user

# Install Docker Compose
log "Installing Docker Compose..."
mkdir -p /usr/lib/docker/cli-plugins
curl -L "https://github.com/docker/compose/releases/download/v5.1.1/docker-compose-$(uname -s)-$(uname -m)" -o /usr/lib/docker/cli-plugins/docker-compose
chmod +x /usr/lib/docker/cli-plugins/docker-compose
# ln -sf /usr/lib/docker/cli-plugins/docker-compose /usr/bin/docker-compose

# Install Docker Buildx
log "Installing Docker Buildx..."
curl -L "https://github.com/docker/buildx/releases/download/v0.32.1/buildx-v0.32.1.linux-amd64" -o /usr/lib/docker/cli-plugins/docker-buildx
chmod +x /usr/lib/docker/cli-plugins/docker-buildx


# Install Git
log "Installing Git..."
dnf install -y git

# Create project directory
log "Creating project directory..."
mkdir -p "/home/ec2-user/${project_name}"
cd "/home/ec2-user/${project_name}"

# Create a setup script for the user to clone and run the project
log "Creating setup instructions..."
cat > /home/ec2-user/SETUP_INSTRUCTIONS.txt <<'EOF'
=================================================================
Greennode Task Engine - Infrastructure Instance Setup Complete
=================================================================

The system is ready! Follow these steps to deploy the application:

1. SSH into this instance:
   ssh -i <your-key>.pem ec2-user@<this-instance-ip>

2. Clone your repository:
   cd /home/ec2-user
   git clone <your-repo-url> greennode-task-engine
   cd greennode-task-engine

3. Start the infrastructure:
   docker-compose -f compose.yaml up -d

4. Check the status:
   docker-compose ps
   docker-compose logs -f

5. Access the services:
   - API Gateway: http://<public-ip>:8080
   - RabbitMQ Management: http://<public-ip>:15672 (user: green_user, pass: green_password)

6. Test the API:
   curl -X POST http://localhost:8080/api/v1/jobs \
     -H "Content-Type: application/json" \
     -d '{"taskType":"matrix_multiplication","complexity":5}'

=================================================================
Installed Software:
=================================================================
- Docker: $(docker --version)
- Docker Compose: $(docker-compose --version)
- Git: $(git --version)

System Resources:
- CPU Cores: $(nproc)
- Memory: $(free -h | grep Mem | awk '{print $2}')
- Disk: $(df -h / | tail -1 | awk '{print $2}')

=================================================================
EOF

# Set proper ownership
chown -R ec2-user:ec2-user /home/ec2-user

# # Install CloudWatch agent for monitoring (optional)
# log "Installing CloudWatch agent..."
# wget -q https://s3.amazonaws.com/amazoncloudwatch-agent/amazon_linux/amd64/latest/amazon-cloudwatch-agent.rpm
# rpm -U ./amazon-cloudwatch-agent.rpm
# rm -f ./amazon-cloudwatch-agent.rpm

log "Infrastructure instance setup complete!"
log "See /home/ec2-user/SETUP_INSTRUCTIONS.txt for next steps"
