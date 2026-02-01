#!/bin/bash

# ============================================
# Quick Start Testing Guide
# ============================================
# Run these commands to test the Docker deployment

set -e

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

echo "========================================="
echo "IoT Blockchain Docker Testing"
echo "========================================="
echo ""

# Step 1: Install Docker (if not installed)
echo "STEP 1: Installing Docker and dependencies"
echo "-------------------------------------------"
echo "Run these commands if not already done:"
echo ""
echo "  sudo apt-get update"
echo "  sudo apt-get install -y docker.io docker-compose jq curl"
echo "  sudo usermod -aG docker \$USER"
echo ""
echo "Then LOGOUT and LOGIN again for group changes to take effect"
echo ""
read -p "Press Enter to continue..."

# Step 2: Verify Docker installation
echo ""
echo "STEP 2: Verifying Docker installation"
echo "--------------------------------------"
sudo docker --version || echo "Docker not found"
sudo docker-compose --version || echo "Docker Compose not found"
print_success "Docker check completed"
echo ""

# Step 3: Build Docker images
echo "STEP 3: Building Docker images"
echo "-------------------------------"
echo "This will take 3-5 minutes on first build..."
echo ""

sudo docker-compose build

if [ $? -eq 0 ]; then
    print_success "Docker images built successfully"
else
    print_error "Docker build failed"
    exit 1
fi

# Step 4: Start the network
echo "STEP 4: Starting blockchain network"
echo "------------------------------------"
sudo docker-compose up -d

if [ $? -eq 0 ]; then
    print_success "Network started"
else
    print_error "Failed to start network"
    exit 1
fi

# Step 5: Wait for nodes to be ready
echo "STEP 5: Waiting for nodes to initialize"
echo "----------------------------------------"
echo "Waiting 60 seconds for nodes to start..."
sleep 60

# Step 6: Check node status
echo ""
echo "STEP 6: Checking node status"
echo "-----------------------------"
sudo docker-compose ps
echo ""

# Step 7: Test health endpoints
echo "STEP 7: Testing health endpoints"
echo "---------------------------------"
echo "Seed Node:"
curl -s http://localhost:8000/api/v1/health | jq '.'
echo ""
echo "Peer Node 1:"
curl -s http://localhost:8001/api/v1/health | jq '.'
echo ""
echo "Peer Node 2:"
curl -s http://localhost:8002/api/v1/health | jq '.'
echo ""

# Step 8: Check network status
echo "STEP 8: Checking network status"
echo "--------------------------------"
echo "Seed Node Status:"
curl -s http://localhost:8000/api/v1/network/status | jq '.'
echo ""

# Step 9: Run basic transaction test
echo "STEP 9: Running basic transaction test"
echo "---------------------------------------"
./test_transaction.sh
echo ""

# Step 10: Run IoT transaction test
echo "STEP 10: Running IoT transaction test"
echo "--------------------------------------"
./test_iot_transaction.sh
echo ""

# Step 11: View logs
echo "STEP 11: Viewing recent logs"
echo "-----------------------------"
echo "Last 20 lines from each node:"
echo ""
echo "=== Seed Node ==="
sudo docker-compose logs --tail=20 seed-node
echo ""
echo "=== Peer Node 1 ==="
sudo docker-compose logs --tail=20 peer-node-1
echo ""
echo "=== Peer Node 2 ==="
sudo docker-compose logs --tail=20 peer-node-2
echo ""

# Summary
echo "========================================="
echo "Testing Complete!"
echo "========================================="
echo ""
echo "Your blockchain network is running with:"
echo "  - Seed Node:  http://localhost:8000"
echo "  - Peer Node 1: http://localhost:8001"
echo "  - Peer Node 2: http://localhost:8002"
echo ""
echo "Next steps:"
echo "  - View logs: sudo docker-compose logs -f"
echo "  - Stop network: sudo docker-compose down"
echo "  - Run more tests: ./test_iot_transaction.sh"
echo ""
echo "For multi-PC deployment, see: vm-setup/VIRTUALBOX_GUIDE.md"
echo ""
