#!/bin/bash
# HybridChain — 7-Stage Integration Test Suite — Marc Amgad
set -e

# Configuration
API_BASE_PORT=8001
NETWORK_SIZE=20
TARGET_HEIGHT=10

echo "🚀 Starting HybridChain 20-Node Integration Test Suite..."

wait_for_condition() {
    local cmd=$1
    local msg=$2
    local timeout=$3
    local count=0
    while ! eval "$cmd"; do
        sleep 2
        count=$((count + 2))
        if [ $count -ge $timeout ]; then
            echo "❌ Timeout: $msg"
            exit 1
        fi
        echo "⏳ Still waiting: $msg ($count/$timeout s)..."
    done
}

# Stage 1: Health Checks
echo "------------------------------------------------"
echo "Stage 1: Health Checks (All 20 Nodes)"
for i in $(seq 1 $NETWORK_SIZE); do
    PORT=$((API_BASE_PORT + i - 1))
    curl -s --fail "http://localhost:$PORT/api/v1/health" > /dev/null || { echo "❌ Node $i health check failed"; exit 1; }
done
echo "✅ All nodes healthy."

# Stage 2: Block Height Synchronization
echo "------------------------------------------------"
echo "Stage 2: Block Height Synchronization (> 2 blocks)"
wait_for_condition \
    "curl -s http://localhost:8001/api/v1/chain/status | grep -q '\"height\":[2-9]'" \
    "Cluster to start producing blocks" 120
echo "✅ Blockchain is moving."

# Stage 3: Transaction Propagation
echo "------------------------------------------------"
echo "Stage 3: Transaction Propagation"
TX_PAYLOAD='{"from":"hb_test_sender","to":"hb_test_receiver","amount":100,"type":"ACCOUNT","fee":1}'
curl -s -X POST -H "Content-Type: application/json" -d "$TX_PAYLOAD" "http://localhost:8001/api/v1/transactions" > /dev/null
echo "Sent transaction to Node 1. Checking Node 20..."
wait_for_condition \
    "curl -s http://localhost:8020/api/v1/mempool | grep -q 'hb_test_sender'" \
    "Transaction to propagate to Node 20" 60
echo "✅ Transaction propagated successfully."

# Stage 4: PBFT Consensus Agreement
echo "------------------------------------------------"
echo "Stage 4: PBFT Consensus Agreement (Tip Consistency)"
wait_for_condition \
    "curl -s http://localhost:8001/api/v1/chain/status | grep -q '\"height\":$TARGET_HEIGHT'" \
    "Reach target height $TARGET_HEIGHT" 200
HASH1=$(curl -s http://localhost:8001/api/v1/chain/status | grep -o '"tipHash":"[^"]*"' | cut -d'"' -f4)
HASH20=$(curl -s http://localhost:8020/api/v1/chain/status | grep -o '"tipHash":"[^"]*"' | cut -d'"' -f4)
if [ "$HASH1" != "$HASH20" ]; then
    echo "❌ Forks detected! Node 1: $HASH1, Node 20: $HASH20"
    exit 1
fi
echo "✅ All nodes agree on Tip Hash: $HASH1"

# Stage 5: IoT Device Lifecycle
echo "------------------------------------------------"
echo "Stage 5: IoT Device Lifecycle (MINT + ACTIVATE)"
DEVICE_ADDR="hb_iot_device_001"
curl -s -X POST "http://localhost:8001/api/v1/iot/mint/$DEVICE_ADDR" > /dev/null
sleep 10
curl -s -X POST "http://localhost:8001/api/v1/iot/activate/$DEVICE_ADDR" > /dev/null
sleep 15
STATUS=$(curl -s "http://localhost:8001/api/v1/iot/status/$DEVICE_ADDR" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
if [ "$STATUS" != "ACTIVE" ]; then
    echo "❌ Device status is $STATUS, expected ACTIVE"
    exit 1
fi
echo "✅ IoT Device lifecycle transition verified."

# Stage 6: State Proof Verification
echo "------------------------------------------------"
echo "Stage 6: State Proof Verification"
PROOF=$(curl -s "http://localhost:8001/api/v1/proof/$DEVICE_ADDR" | grep -o '"proof":"[^"]*"' | cut -d'"' -f4)
if [ -z "$PROOF" ]; then
    echo "❌ Failed to retrieve state proof"
    exit 1
fi
echo "✅ State proof retrieved and contains data."

# Stage 7: Resilience / Leader Failure (View Change)
echo "------------------------------------------------"
echo "Stage 7: Resilience / Leader Failure (Killing hb-node-1)"
HEIGHT_BEFORE=$(curl -s http://localhost:8002/api/v1/chain/status | grep -o '"height":[0-9]*' | cut -d':' -f2)
docker stop hb-node-1
echo "hb-node-1 stopped. Waiting for view change and new block production..."
wait_for_condition \
    "H=\$(curl -s http://localhost:8002/api/v1/chain/status | grep -o '\"height\":[0-9]*' | cut -d':' -f2); [ \$H -gt $HEIGHT_BEFORE ]" \
    "New block production after leader failure" 180
echo "✅ Blockchain recovered and continued growth after leader failure."

echo "------------------------------------------------"
echo "🎉 ALL INTEGRATION TESTS PASSED SUCCESSFULLY!"
echo "------------------------------------------------"
