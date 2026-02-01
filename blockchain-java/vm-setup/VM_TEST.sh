#!/bin/bash

# VM_TEST.sh
# Run this on the VM (Seed or Peer) to verify the node.

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
echo "Blockchain Node VM Test"
echo "========================================="

# 1. Check Docker
if ! command -v docker &> /dev/null; then
    print_error "Docker is not installed."
    exit 1
fi

if ! docker ps &> /dev/null; then
    print_error "Cannot connect to Docker daemon. Try 'sudo' or check permissions."
    exit 1
fi
print_success "Docker is operational"

# 2. Check Containers
echo "Checking running containers..."
CONTAINERS=$(docker ps --format "{{.Names}}")
if [[ -z "$CONTAINERS" ]]; then
    print_error "No containers running."
    echo "Try: docker-compose up -d"
else
    print_success "Running containers: $CONTAINERS"
fi

# 3. Check Health
echo ""
echo "Checking Node Health (localhost:8000)..."
if curl -s http://localhost:8000/api/v1/health | grep -q "UP"; then
    print_success "Node is HEALTHY"
    curl -s http://localhost:8000/api/v1/health
else
    print_error "Node Health check FAILED or invalid response"
    curl -v http://localhost:8000/api/v1/health 2>&1
fi

echo ""
echo "Checking Network Status..."
curl -s http://localhost:8000/api/v1/network/status
echo ""

