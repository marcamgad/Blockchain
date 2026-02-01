#!/bin/bash

# ============================================
# View Blockchain Node Logs
# ============================================
# This script displays logs from blockchain nodes with color coding

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# Default: show all nodes
NODE_FILTER=""

# Parse arguments
if [ $# -gt 0 ]; then
    case "$1" in
        seed|seed-node)
            NODE_FILTER="blockchain-seed"
            ;;
        peer1|peer-1)
            NODE_FILTER="blockchain-peer-1"
            ;;
        peer2|peer-2)
            NODE_FILTER="blockchain-peer-2"
            ;;
        *)
            echo -e "${YELLOW}Usage: $0 [seed|peer1|peer2]${NC}"
            echo -e "  No argument: show all nodes"
            echo -e "  seed: show only seed node"
            echo -e "  peer1: show only peer node 1"
            echo -e "  peer2: show only peer node 2"
            exit 1
            ;;
    esac
fi

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Blockchain Node Logs${NC}"
echo -e "${BLUE}========================================${NC}\n"

if [ -z "$NODE_FILTER" ]; then
    echo -e "${CYAN}Showing logs from all nodes (press Ctrl+C to exit)${NC}\n"
    docker-compose logs -f --tail=100
else
    echo -e "${CYAN}Showing logs from $NODE_FILTER (press Ctrl+C to exit)${NC}\n"
    docker-compose logs -f --tail=100 "$NODE_FILTER"
fi
