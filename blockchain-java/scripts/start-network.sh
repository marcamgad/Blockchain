#!/bin/bash

# ============================================
# Start Blockchain Network
# ============================================
# This script builds Docker images and starts the multi-node blockchain network

set -e

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Starting Blockchain Network${NC}"
echo -e "${BLUE}========================================${NC}\n"

# Step 1: Build Docker images
echo -e "${YELLOW}Building Docker images...${NC}"
docker-compose build

echo -e "${GREEN}✓ Docker images built successfully${NC}\n"

# Step 2: Start the network
echo -e "${YELLOW}Starting blockchain nodes...${NC}"
docker-compose up -d

echo -e "${GREEN}✓ Blockchain network started${NC}\n"

# Step 3: Wait for nodes to be healthy
echo -e "${YELLOW}Waiting for nodes to be ready...${NC}"
sleep 10

# Check node status
echo -e "\n${BLUE}Node Status:${NC}"
docker-compose ps

# Step 4: Display connection information
echo -e "\n${BLUE}========================================${NC}"
echo -e "${BLUE}Network Information${NC}"
echo -e "${BLUE}========================================${NC}\n"

echo -e "${GREEN}Seed Node:${NC}"
echo -e "  API: http://localhost:8000"
echo -e "  P2P: localhost:6001"
echo -e "  Health: http://localhost:8000/health"
echo ""

echo -e "${GREEN}Peer Node 1:${NC}"
echo -e "  API: http://localhost:8001"
echo -e "  P2P: localhost:6002"
echo -e "  Health: http://localhost:8001/health"
echo ""

echo -e "${GREEN}Peer Node 2:${NC}"
echo -e "  API: http://localhost:8002"
echo -e "  P2P: localhost:6003"
echo -e "  Health: http://localhost:8002/health"
echo ""

echo -e "${BLUE}========================================${NC}"
echo -e "${GREEN}✓ Network started successfully!${NC}"
echo -e "${BLUE}========================================${NC}\n"

echo -e "${YELLOW}Next steps:${NC}"
echo -e "  1. View logs: ./scripts/view-logs.sh"
echo -e "  2. Run tests: ./test_transaction.sh"
echo -e "  3. Stop network: ./scripts/stop-network.sh"
echo ""
