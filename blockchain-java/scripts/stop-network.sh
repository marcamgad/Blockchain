#!/bin/bash

# ============================================
# Stop Blockchain Network
# ============================================
# This script stops the blockchain network and optionally clears data

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Stopping Blockchain Network${NC}"
echo -e "${BLUE}========================================${NC}\n"

# Parse arguments
CLEAR_DATA=false
if [ "$1" == "--clear-data" ] || [ "$1" == "-c" ]; then
    CLEAR_DATA=true
    echo -e "${YELLOW}⚠ Data will be cleared${NC}\n"
fi

# Stop containers
echo -e "${YELLOW}Stopping containers...${NC}"
docker-compose down

echo -e "${GREEN}✓ Containers stopped${NC}\n"

# Clear data if requested
if [ "$CLEAR_DATA" = true ]; then
    echo -e "${YELLOW}Clearing blockchain data...${NC}"
    docker volume rm blockchain-java_seed_data blockchain-java_peer1_data blockchain-java_peer2_data 2>/dev/null || true
    echo -e "${GREEN}✓ Data cleared${NC}\n"
fi

echo -e "${BLUE}========================================${NC}"
echo -e "${GREEN}✓ Network stopped successfully${NC}"
echo -e "${BLUE}========================================${NC}\n"

if [ "$CLEAR_DATA" = false ]; then
    echo -e "${YELLOW}Note:${NC} Blockchain data is preserved in Docker volumes"
    echo -e "To clear data, run: $0 --clear-data"
    echo ""
fi
