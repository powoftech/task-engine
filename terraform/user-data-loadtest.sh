#!/bin/bash
set -e

# Logging function
log() {
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] $*" | tee -a /var/log/user-data.log
}

log "Starting load test instance setup..."

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

# Install Git and other utilities
log "Installing Git and utilities..."
dnf install -y git wget

# Install k6 using Docker (recommended approach)
log "Pulling k6 Docker image..."
docker pull grafana/k6:latest

# Create k6 scripts directory
log "Creating k6 scripts directory..."
mkdir -p /home/ec2-user/load-tests
cd /home/ec2-user/load-tests

# Create a startup script for running load tests
log "Creating load test runner script..."
cat > /home/ec2-user/load-tests/run-loadtest.sh <<'SCRIPT'
#!/bin/bash
# Load Test Runner Script for Greennode Task Engine

set -e

# Configuration
INFRASTRUCTURE_IP="${infrastructure_private_ip}"
API_URL="http://$${INFRASTRUCTURE_IP}:8080"
RESULTS_DIR="/home/ec2-user/load-tests/results"

# Create results directory if it doesn't exist
mkdir -p "$RESULTS_DIR"

# Function to display usage
usage() {
    cat <<EOF
Usage: $0 [OPTIONS]

Options:
    -u, --url URL       API Gateway URL (default: $API_URL)
    -s, --script PATH   Path to k6 script (default: ./k6-script.js)
    -o, --output DIR    Output directory for results (default: $RESULTS_DIR)
    -h, --help          Display this help message

Examples:
    # Run with default settings:
    $0

    # Run with custom URL:
    $0 -u http://example.com:8080

    # Run with custom script:
    $0 -s ./custom-test.js

EOF
    exit 0
}

# Parse command line arguments
SCRIPT_PATH="./k6-script.js"
while [[ $# -gt 0 ]]; do
    case $1 in
        -u|--url)
            API_URL="$2"
            shift 2
            ;;
        -s|--script)
            SCRIPT_PATH="$2"
            shift 2
            ;;
        -o|--output)
            RESULTS_DIR="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo "Unknown option: $1"
            usage
            ;;
    esac
done

# Check if script exists
if [ ! -f "$SCRIPT_PATH" ]; then
    echo "Error: k6 script not found at $SCRIPT_PATH"
    echo "Please copy your k6-script.js to /home/ec2-user/load-tests/"
    exit 1
fi

# Generate timestamp for results
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULT_FILE="$RESULTS_DIR/loadtest_$TIMESTAMP.json"
LOG_FILE="$RESULTS_DIR/loadtest_$TIMESTAMP.log"

echo "========================================"
echo "Greennode Task Engine - Load Test"
echo "========================================"
echo "API URL: $API_URL"
echo "Script: $SCRIPT_PATH"
echo "Results: $RESULT_FILE"
echo "========================================"
echo ""

# Check if infrastructure is reachable
echo "Checking infrastructure connectivity..."
if ! curl -s --max-time 5 "$API_URL/actuator/health" > /dev/null 2>&1; then
    echo "Warning: Cannot reach API Gateway at $API_URL"
    echo "Make sure the infrastructure instance is running and healthy."
    read -p "Continue anyway? (y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# Run k6 load test
echo "Starting k6 load test..."
docker run --rm \
    -v "$(pwd):/scripts" \
    -v "$RESULTS_DIR:/results" \
    -e API_URL="$API_URL" \
    --user root \
    grafana/k6:latest \
    run \
    --out json="/results/loadtest_$TIMESTAMP.json" \
    "/scripts/$(basename $SCRIPT_PATH)" \
    | sudo tee "$LOG_FILE"

echo ""
echo "========================================"
echo "Load test complete!"
echo "Results saved to: $RESULT_FILE"
echo "Logs saved to: $LOG_FILE"
echo "========================================"
SCRIPT

chmod +x /home/ec2-user/load-tests/run-loadtest.sh

# Create setup instructions
log "Creating setup instructions..."
cat > /home/ec2-user/SETUP_INSTRUCTIONS.txt <<'EOF'
=================================================================
Greennode Task Engine - Load Test Instance Setup Complete
=================================================================

The system is ready! Follow these steps to run load tests:

1. SSH into this instance:
   ssh -i <your-key>.pem ec2-user@<this-instance-ip>

2. Copy your k6 test script:
   cd /home/ec2-user/load-tests

   # Either clone the repository:
   git clone <your-repo-url> temp
   cp temp/infra/load-tests/k6-script.js ./
   rm -rf temp

   # Or manually create/copy the k6-script.js file here

3. Run the load test:
   ./run-loadtest.sh

4. View results:
   ls -lh results/
   cat results/loadtest_*.log

=================================================================
Load Test Configuration:
===========================================infrastructure_private_ip======================
Target Infrastructure IP: ${infrastructure_private_ip}
Default API URL: http://${infrastructure_private_ip}:8080

To test against the public IP instead:
   ./run-loadtest.sh -u http://<public-ip>:8080

To use a custom k6 script:
   ./run-loadtest.sh -s /path/to/custom-script.js

=================================================================
Installed Software:
=================================================================
- Docker: $(docker --version)
- k6: Available via Docker (grafana/k6:latest)
- Git: $(git --version)

System Resources:
- CPU Cores: $(nproc)
- Memory: $(free -h | grep Mem | awk '{print $2}')
- Disk: $(df -h / | tail -1 | awk '{print $2}')

=================================================================
EOF

# Set proper ownership
chown -R ec2-user:ec2-user /home/ec2-user

log "Load test instance setup complete!"
log "See /home/ec2-user/SETUP_INSTRUCTIONS.txt for next steps"
