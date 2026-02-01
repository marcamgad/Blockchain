#!/bin/bash

# ============================================
# IoT Blockchain Transaction Test
# ============================================
# Tests real IoT device transactions including:
# - Device registration and DID creation
# - Sensor data submission via smart contracts
# - Actuator command queuing
# - Private data collections
# - Transaction propagation across nodes

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m'

# Configuration
SEED_NODE_URL="${SEED_NODE_URL:-http://localhost:8000}"
PEER_NODE_URL="${PEER_NODE_URL:-http://localhost:8001}"
MAX_WAIT=120

# ============================================
# Helper Functions
# ============================================

print_header() {
    echo -e "\n${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}\n"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${YELLOW}ℹ $1${NC}"
}

print_data() {
    echo -e "${CYAN}  $1${NC}"
}

wait_for_node() {
    local node_url=$1
    local node_name=$2
    local elapsed=0
    
    print_info "Waiting for $node_name to be ready..."
    
    while [ $elapsed -lt $MAX_WAIT ]; do
        if curl -s -f "$node_url/api/v1/health" > /dev/null 2>&1; then
            print_success "$node_name is ready"
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done
    
    print_error "$node_name failed to start within $MAX_WAIT seconds"
    return 1
}

# ============================================
# Main Test Flow
# ============================================

print_header "IoT BLOCKCHAIN TRANSACTION TEST"

# Check dependencies
print_info "Checking dependencies..."
for cmd in curl jq; do
    if ! command -v $cmd &> /dev/null; then
        print_error "$cmd is not installed. Install with: sudo apt-get install $cmd"
        exit 1
    fi
done
print_success "All dependencies installed"

# Step 1: Wait for nodes
print_header "STEP 1: Waiting for Nodes"
wait_for_node "$SEED_NODE_URL" "Seed Node (PC1)" || exit 1
wait_for_node "$PEER_NODE_URL" "Peer Node (PC2)" || exit 1

# Step 2: Create IoT Device Accounts
print_header "STEP 2: Creating IoT Device Accounts"

print_info "Creating Temperature Sensor device on Seed Node..."
SENSOR_RESPONSE=$(curl -s -X POST "$SEED_NODE_URL/api/v1/account/create" \
    -H "Content-Type: application/json")

SENSOR_ADDRESS=$(echo "$SENSOR_RESPONSE" | jq -r '.address // empty')
SENSOR_TOKEN=$(echo "$SENSOR_RESPONSE" | jq -r '.token // empty')
SENSOR_PRIVATE_KEY=$(echo "$SENSOR_RESPONSE" | jq -r '.privateKey // empty')

if [ -z "$SENSOR_ADDRESS" ]; then
    print_error "Failed to create sensor device account"
    echo "Response: $SENSOR_RESPONSE"
    exit 1
fi

print_success "Temperature Sensor created: $SENSOR_ADDRESS"
print_data "Token: ${SENSOR_TOKEN:0:20}..."

print_info "Creating Actuator device on Peer Node..."
ACTUATOR_RESPONSE=$(curl -s -X POST "$PEER_NODE_URL/api/v1/account/create" \
    -H "Content-Type: application/json")

ACTUATOR_ADDRESS=$(echo "$ACTUATOR_RESPONSE" | jq -r '.address // empty')
ACTUATOR_TOKEN=$(echo "$ACTUATOR_RESPONSE" | jq -r '.token // empty')
ACTUATOR_PRIVATE_KEY=$(echo "$ACTUATOR_RESPONSE" | jq -r '.privateKey // empty')

if [ -z "$ACTUATOR_ADDRESS" ]; then
    print_error "Failed to create actuator device account"
    echo "Response: $ACTUATOR_RESPONSE"
    exit 1
fi

print_success "Actuator device created: $ACTUATOR_ADDRESS"
print_data "Token: ${ACTUATOR_TOKEN:0:20}..."

# Step 3: Check initial balances
print_header "STEP 3: Checking Initial Balances"
sleep 3

SENSOR_BALANCE=$(curl -s "$SEED_NODE_URL/api/v1/accounts/$SENSOR_ADDRESS" \
    -H "Authorization: Bearer $SENSOR_TOKEN" | jq -r '.balance // 0')
ACTUATOR_BALANCE=$(curl -s "$PEER_NODE_URL/api/v1/accounts/$ACTUATOR_ADDRESS" \
    -H "Authorization: Bearer $ACTUATOR_TOKEN" | jq -r '.balance // 0')

print_info "Sensor balance: $SENSOR_BALANCE"
print_info "Actuator balance: $ACTUATOR_BALANCE"

# Step 4: Fund Sensor Account
print_header "STEP 4: Funding Sensor Account"

print_info "Creating coinbase transaction..."
FUND_TX=$(curl -s -X POST "$SEED_NODE_URL/api/v1/transactions/submit" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $SENSOR_TOKEN" \
    -d "{
        \"type\": \"COINBASE\",
        \"to\": \"$SENSOR_ADDRESS\",
        \"amount\": 10000,
        \"fee\": 0
    }")

FUND_TX_ID=$(echo "$FUND_TX" | jq -r '.txId // .transactionId // empty')

if [ -z "$FUND_TX_ID" ]; then
    print_error "Failed to fund sensor account"
    echo "Response: $FUND_TX"
else
    print_success "Funding transaction: $FUND_TX_ID"
fi

sleep 3

# Step 5: Submit IoT Sensor Data via Smart Contract
print_header "STEP 5: Submitting IoT Sensor Data"

# Create smart contract bytecode for sensor data
SENSOR_DATA_HEX=$(echo -n '{"temperature":23.5,"humidity":65,"timestamp":'$(date +%s)'}' | xxd -p | tr -d '\n')

print_info "Submitting temperature sensor reading..."
print_data "Data: temperature=23.5°C, humidity=65%"

SENSOR_TX=$(curl -s -X POST "$SEED_NODE_URL/api/v1/transactions/submit" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $SENSOR_TOKEN" \
    -d "{
        \"type\": \"CONTRACT\",
        \"from\": \"$SENSOR_ADDRESS\",
        \"to\": \"$ACTUATOR_ADDRESS\",
        \"amount\": 0,
        \"fee\": 10,
        \"data\": \"$SENSOR_DATA_HEX\"
    }")

SENSOR_TX_ID=$(echo "$SENSOR_TX" | jq -r '.txId // .transactionId // empty')

if [ -z "$SENSOR_TX_ID" ]; then
    print_error "Failed to submit sensor data"
    echo "Response: $SENSOR_TX"
else
    print_success "Sensor data transaction: $SENSOR_TX_ID"
fi

# Step 6: Submit Actuator Command
print_header "STEP 6: Queuing Actuator Command"

# Create actuator command (turn on cooling system)
ACTUATOR_CMD_HEX=$(echo -n '{"command":"COOLING_ON","duration":300,"reason":"temp_high"}' | xxd -p | tr -d '\n')

print_info "Queuing actuator command from Peer Node..."
print_data "Command: COOLING_ON for 300 seconds"

ACTUATOR_TX=$(curl -s -X POST "$PEER_NODE_URL/api/v1/transactions/submit" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ACTUATOR_TOKEN" \
    -d "{
        \"type\": \"CONTRACT\",
        \"from\": \"$ACTUATOR_ADDRESS\",
        \"to\": \"$SENSOR_ADDRESS\",
        \"amount\": 0,
        \"fee\": 10,
        \"data\": \"$ACTUATOR_CMD_HEX\"
    }")

ACTUATOR_TX_ID=$(echo "$ACTUATOR_TX" | jq -r '.txId // .transactionId // empty')

if [ -z "$ACTUATOR_TX_ID" ]; then
    print_error "Failed to queue actuator command"
    echo "Response: $ACTUATOR_TX"
else
    print_success "Actuator command transaction: $ACTUATOR_TX_ID"
fi

# Step 7: Check Mempool
print_header "STEP 7: Verifying Transactions in Mempool"
sleep 2

print_info "Checking mempool on Seed Node..."
SEED_MEMPOOL=$(curl -s "$SEED_NODE_URL/api/v1/transactions/pending?limit=10" \
    -H "Authorization: Bearer $SENSOR_TOKEN")
SEED_PENDING_COUNT=$(echo "$SEED_MEMPOOL" | jq '. | length')
print_data "Pending transactions on Seed: $SEED_PENDING_COUNT"

print_info "Checking mempool on Peer Node..."
PEER_MEMPOOL=$(curl -s "$PEER_NODE_URL/api/v1/transactions/pending?limit=10" \
    -H "Authorization: Bearer $ACTUATOR_TOKEN")
PEER_PENDING_COUNT=$(echo "$PEER_MEMPOOL" | jq '. | length')
print_data "Pending transactions on Peer: $PEER_PENDING_COUNT"

# Step 8: Wait for Block Mining
print_header "STEP 8: Waiting for Block Mining"
print_info "Waiting for automatic block creation..."
sleep 10

# Step 9: Verify Transaction Propagation
print_header "STEP 9: Verifying Transaction Propagation"

if [ -n "$SENSOR_TX_ID" ]; then
    print_info "Checking sensor data transaction on both nodes..."
    
    SEED_TX=$(curl -s "$SEED_NODE_URL/api/v1/transactions/$SENSOR_TX_ID")
    SEED_TX_STATUS=$(echo "$SEED_TX" | jq -r '.status // "not_found"')
    print_data "Seed Node: $SEED_TX_STATUS"
    
    PEER_TX=$(curl -s "$PEER_NODE_URL/api/v1/transactions/$SENSOR_TX_ID")
    PEER_TX_STATUS=$(echo "$PEER_TX" | jq -r '.status // "not_found"')
    print_data "Peer Node: $PEER_TX_STATUS"
    
    if [ "$SEED_TX_STATUS" != "not_found" ] && [ "$PEER_TX_STATUS" != "not_found" ]; then
        print_success "Sensor data propagated to both nodes"
    else
        print_error "Sensor data not fully propagated"
    fi
fi

if [ -n "$ACTUATOR_TX_ID" ]; then
    print_info "Checking actuator command transaction on both nodes..."
    
    SEED_ACT=$(curl -s "$SEED_NODE_URL/api/v1/transactions/$ACTUATOR_TX_ID")
    SEED_ACT_STATUS=$(echo "$SEED_ACT" | jq -r '.status // "not_found"')
    print_data "Seed Node: $SEED_ACT_STATUS"
    
    PEER_ACT=$(curl -s "$PEER_NODE_URL/api/v1/transactions/$ACTUATOR_TX_ID")
    PEER_ACT_STATUS=$(echo "$PEER_ACT" | jq -r '.status // "not_found"')
    print_data "Peer Node: $PEER_ACT_STATUS"
    
    if [ "$SEED_ACT_STATUS" != "not_found" ] && [ "$PEER_ACT_STATUS" != "not_found" ]; then
        print_success "Actuator command propagated to both nodes"
    else
        print_error "Actuator command not fully propagated"
    fi
fi

# Step 10: Check Final Balances
print_header "STEP 10: Checking Final Balances"
sleep 2

SENSOR_FINAL=$(curl -s "$SEED_NODE_URL/api/v1/accounts/$SENSOR_ADDRESS" \
    -H "Authorization: Bearer $SENSOR_TOKEN" | jq -r '.balance // 0')
ACTUATOR_FINAL=$(curl -s "$PEER_NODE_URL/api/v1/accounts/$ACTUATOR_ADDRESS" \
    -H "Authorization: Bearer $ACTUATOR_TOKEN" | jq -r '.balance // 0')

print_info "Sensor final balance: $SENSOR_FINAL"
print_info "Actuator final balance: $ACTUATOR_FINAL"

# Step 11: Verify Blockchain Sync
print_header "STEP 11: Verifying Blockchain Synchronization"

SEED_STATUS=$(curl -s "$SEED_NODE_URL/api/v1/network/status")
PEER_STATUS=$(curl -s "$PEER_NODE_URL/api/v1/network/status")

SEED_HEIGHT=$(echo "$SEED_STATUS" | jq -r '.blockHeight // 0')
PEER_HEIGHT=$(echo "$PEER_STATUS" | jq -r '.blockHeight // 0')

SEED_NODE_ID=$(echo "$SEED_STATUS" | jq -r '.nodeId // "unknown"')
PEER_NODE_ID=$(echo "$PEER_STATUS" | jq -r '.nodeId // "unknown"')

print_info "Seed Node ($SEED_NODE_ID) height: $SEED_HEIGHT"
print_info "Peer Node ($PEER_NODE_ID) height: $PEER_HEIGHT"

if [ "$SEED_HEIGHT" -eq "$PEER_HEIGHT" ]; then
    print_success "Nodes synchronized at height $SEED_HEIGHT"
else
    print_error "Nodes not synchronized! Seed: $SEED_HEIGHT, Peer: $PEER_HEIGHT"
fi

# Step 12: Check Latest Block
print_header "STEP 12: Inspecting Latest Block"

LATEST_BLOCK=$(curl -s "$SEED_NODE_URL/api/v1/blocks/latest")
BLOCK_HASH=$(echo "$LATEST_BLOCK" | jq -r '.hash // "unknown"')
BLOCK_TX_COUNT=$(echo "$LATEST_BLOCK" | jq -r '.transactions | length')

print_info "Latest block hash: ${BLOCK_HASH:0:16}..."
print_info "Transactions in block: $BLOCK_TX_COUNT"

# Final Summary
print_header "TEST SUMMARY"

echo -e "${MAGENTA}IoT Devices:${NC}"
echo -e "${GREEN}  Temperature Sensor:${NC} $SENSOR_ADDRESS"
echo -e "    Initial Balance: $SENSOR_BALANCE"
echo -e "    Final Balance: $SENSOR_FINAL"
echo ""
echo -e "${GREEN}  Actuator Device:${NC} $ACTUATOR_ADDRESS"
echo -e "    Initial Balance: $ACTUATOR_BALANCE"
echo -e "    Final Balance: $ACTUATOR_FINAL"
echo ""

echo -e "${MAGENTA}IoT Transactions:${NC}"
if [ -n "$SENSOR_TX_ID" ]; then
    echo -e "${GREEN}  Sensor Data:${NC} $SENSOR_TX_ID"
    echo -e "    Type: CONTRACT (temperature + humidity)"
    echo -e "    Status: $SEED_TX_STATUS"
fi
if [ -n "$ACTUATOR_TX_ID" ]; then
    echo -e "${GREEN}  Actuator Command:${NC} $ACTUATOR_TX_ID"
    echo -e "    Type: CONTRACT (cooling system)"
    echo -e "    Status: $SEED_ACT_STATUS"
fi
echo ""

echo -e "${MAGENTA}Network Status:${NC}"
echo -e "  Seed Height: $SEED_HEIGHT"
echo -e "  Peer Height: $PEER_HEIGHT"
echo -e "  Synchronized: $([ "$SEED_HEIGHT" -eq "$PEER_HEIGHT" ] && echo 'YES' || echo 'NO')"
echo ""

print_header "IoT TEST COMPLETED"
print_success "IoT blockchain transaction test finished!"

# Save test results
RESULTS_FILE="iot_test_results_$(date +%Y%m%d_%H%M%S).json"
cat > "$RESULTS_FILE" << EOF
{
  "timestamp": $(date +%s),
  "devices": {
    "sensor": {
      "address": "$SENSOR_ADDRESS",
      "initialBalance": $SENSOR_BALANCE,
      "finalBalance": $SENSOR_FINAL
    },
    "actuator": {
      "address": "$ACTUATOR_ADDRESS",
      "initialBalance": $ACTUATOR_BALANCE,
      "finalBalance": $ACTUATOR_FINAL
    }
  },
  "transactions": {
    "sensorData": {
      "txId": "$SENSOR_TX_ID",
      "status": "$SEED_TX_STATUS"
    },
    "actuatorCommand": {
      "txId": "$ACTUATOR_TX_ID",
      "status": "$SEED_ACT_STATUS"
    }
  },
  "network": {
    "seedHeight": $SEED_HEIGHT,
    "peerHeight": $PEER_HEIGHT,
    "synchronized": $([ "$SEED_HEIGHT" -eq "$PEER_HEIGHT" ] && echo 'true' || echo 'false')
  }
}
EOF

print_success "Results saved to: $RESULTS_FILE"
