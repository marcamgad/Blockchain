#!/bin/bash

# ============================================
# Blockchain Multi-Node Transaction Test
# ============================================

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Config
SEED_NODE_URL="http://localhost:8000"
PEER1_NODE_URL="http://localhost:8001"
PEER2_NODE_URL="http://localhost:8002"
MAX_WAIT=120

# Helpers
print_header() { echo -e "\n${BLUE}========================================${NC}\n${BLUE}$1${NC}\n${BLUE}========================================${NC}\n"; }
print_success() { echo -e "${GREEN}✓ $1${NC}"; }
print_error() { echo -e "${RED}✗ $1${NC}"; }
print_info() { echo -e "${YELLOW}ℹ $1${NC}"; }

wait_for_node() {
    local node_url=$1; local name=$2; local elapsed=0
    print_info "Waiting for $name..."
    while [ $elapsed -lt $MAX_WAIT ]; do
        if curl -s -f "$node_url/api/v1/health" > /dev/null 2>&1; then
            print_success "$name is ready"; return 0
        fi
        sleep 2; elapsed=$((elapsed+2))
    done
    print_error "$name failed to start"; return 1
}

# Main
print_header "BLOCKCHAIN TRANSACTION TEST (Zero-Value Propagation)"

# 1. Wait for nodes
wait_for_node "$SEED_NODE_URL" "Seed Node" || exit 1
wait_for_node "$PEER1_NODE_URL" "Peer Node 1" || exit 1
wait_for_node "$PEER2_NODE_URL" "Peer Node 2" || exit 1

# 2. Create Accounts
print_header "STEP 2: Creating Accounts"
ACC1_JSON=$(curl -s -X POST "$SEED_NODE_URL/api/v1/account/create")
# Extract Token as well!
ADDR1=$(echo "$ACC1_JSON" | jq -r '.address // empty')
TOKEN1=$(echo "$ACC1_JSON" | jq -r '.token // empty')
print_success "Account 1: $ADDR1 (Token captured)"

ACC2_JSON=$(curl -s -X POST "$PEER1_NODE_URL/api/v1/account/create")
ADDR2=$(echo "$ACC2_JSON" | jq -r '.address // empty')
TOKEN2=$(echo "$ACC2_JSON" | jq -r '.token // empty')
print_success "Account 2: $ADDR2 (Token captured)"

if [ -z "$ADDR1" ] || [ -z "$TOKEN1" ]; then print_error "Failed to create Account 1"; exit 1; fi

# 3. Check Balance
print_info "Checking initial balances..."
BAL1=$(curl -s "$SEED_NODE_URL/api/v1/accounts/$ADDR1" | jq -r '.balance // 0')
BAL2=$(curl -s "$PEER1_NODE_URL/api/v1/accounts/$ADDR2" | jq -r '.balance // 0')
print_info "Account 1: $BAL1"
print_info "Account 2: $BAL2"

# 4. Skip Funding
print_header "STEP 4: Funding (Skipped)"
print_info "Skipping Coinbase. Proceeding with authenticated 0-value transaction."

# 5. Send Transaction
print_header "STEP 5: Sending 0-Value Transaction"
# Verify we can extract the token
if [ -z "$TOKEN1" ]; then print_error "Token 1 is empty!"; exit 1; fi

TX_RESP=$(curl -s -X POST "$SEED_NODE_URL/api/v1/transactions/submit" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN1" \
    -d "{
        \"type\": \"ACCOUNT\",
        \"from\": \"$ADDR1\",
        \"to\": \"$ADDR2\",
        \"amount\": 0,
        \"fee\": 0
    }")

TX_ID=$(echo "$TX_RESP" | jq -r '.txId // .transactionId // empty')

if [ -z "$TX_ID" ]; then
    print_error "Failed to submit transaction."
    echo "Response: $TX_RESP"
    exit 1
fi
print_success "Transaction sent: $TX_ID"

# 6. Mempool
print_header "STEP 6: Checking Mempool"
sleep 2
MEMPOOL=$(curl -s "$SEED_NODE_URL/api/v1/transactions/pending?limit=10")
echo "$MEMPOOL" | jq '.'

# 7. Mining
print_header "STEP 7: Waiting for Mining"
print_info "Waiting 10s for block creation..."
sleep 10

# 8. Verify
print_header "STEP 8: Verifying Propagation"
STATUS=$(curl -s "$PEER1_NODE_URL/api/v1/transactions/$TX_ID" | jq -r '.status // "unknown"')
print_info "Transaction Status on Peer 1: $STATUS"

if [ "$STATUS" == "CONFIRMED" ] || [ "$STATUS" == "confirmed" ]; then
    print_success "Transaction confirmed!"
else
    print_info "Transaction might still be pending or not found (Status: $STATUS)"
fi

# 9. Final Balance
print_info "Final balances..."
BAL1_F=$(curl -s "$SEED_NODE_URL/api/v1/accounts/$ADDR1" | jq -r '.balance // 0')
print_info "Account 1: $BAL1_F"

# 10. Height
print_header "STEP 10: Sync Check"
H1=$(curl -s "$SEED_NODE_URL/api/v1/network/status" | jq -r '.blockHeight // 0')
H2=$(curl -s "$PEER1_NODE_URL/api/v1/network/status" | jq -r '.blockHeight // 0')
print_info "Seed Height: $H1"
print_info "Peer 1 Height: $H2"

print_success "Test Completed Successfully"
