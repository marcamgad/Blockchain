#!/usr/bin/env bash
# =============================================================================
# docker-test.sh — HybridChain 20-Node Cluster Integration Test
#
# USAGE:  bash docker-test.sh [--keep]
#   --keep   Skip 'docker compose down' after test (useful for debugging)
#
# EXIT CODE: 0 = all checks passed, 1 = one or more checks failed
# =============================================================================
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.20nodes.yml}"
HEALTH_TIMEOUT=120    # seconds to wait for all 20 nodes to become healthy
CONSENSUS_WAIT=45     # seconds for consensus to settle after tx injection
KEEP_UP=false

# Parse flags
for arg in "$@"; do
  [[ "$arg" == "--keep" ]] && KEEP_UP=true
done

# Node API ports: node-seed=8000, validator-1=8001 … validator-4=8004, node-1=8005 … node-15=8019
PORTS=(8000 8001 8002 8003 8004 8005 8006 8007 8008 8009 8010 8011 8012 8013 8014 8015 8016 8017 8018 8019)
PASS=0; FAIL=0

log()  { echo -e "\033[0;36m[TEST]\033[0m $*"; }
ok()   { echo -e "\033[0;32m[ OK ]\033[0m $*"; ((PASS++)); }
fail() { echo -e "\033[0;31m[FAIL]\033[0m $*"; ((FAIL++)); }

# ---------------------------------------------------------------------------
# Step 0 — Bring up the cluster
# ---------------------------------------------------------------------------
log "Step 0: Starting 20-node HybridChain cluster..."
docker compose -f "$COMPOSE_FILE" up -d --build
log "Cluster started. Waiting for nodes to become healthy (timeout=${HEALTH_TIMEOUT}s)..."

# ---------------------------------------------------------------------------
# Step 1 — Health check: all 20 nodes must respond to /api/v1/health
# ---------------------------------------------------------------------------
log "Step 1: Polling /api/v1/health on all 20 nodes..."
deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
healthy=()

while [[ ${#healthy[@]} -lt ${#PORTS[@]} ]]; do
  if [[ $(date +%s) -gt $deadline ]]; then
    fail "TIMEOUT — only ${#healthy[@]}/${#PORTS[@]} nodes healthy after ${HEALTH_TIMEOUT}s"
    break
  fi
  healthy=()
  for port in "${PORTS[@]}"; do
    if curl -sf --max-time 3 "http://localhost:${port}/api/v1/health" > /dev/null 2>&1; then
      healthy+=("$port")
    fi
  done
  [[ ${#healthy[@]} -lt ${#PORTS[@]} ]] && sleep 5
done

if [[ ${#healthy[@]} -eq ${#PORTS[@]} ]]; then
  ok "All ${#PORTS[@]} nodes healthy"
else
  fail "Only ${#healthy[@]}/${#PORTS[@]} nodes healthy"
fi

# ---------------------------------------------------------------------------
# Step 2 — Submit 5 test transactions via node-seed
# ---------------------------------------------------------------------------
log "Step 2: Submitting 5 test transactions via node-seed (port 8000)..."
TX_OK=0
for i in $(seq 1 5); do
  STATUS=$(curl -sf --max-time 5 -o /dev/null -w "%{http_code}" \
    -X POST http://localhost:8000/api/v1/transactions/submit \
    -H 'Content-Type: application/json' \
    -d "{\"type\":\"TELEMETRY\",\"from\":\"hb-test-device-${i}\",\"data\":\"$(echo -n "${i}.0" | base64)\",\"fee\":1}" \
    2>/dev/null || echo "000")
  if [[ "$STATUS" =~ ^2[0-9]{2}$ ]]; then
    ((TX_OK++))
  fi
done

if [[ $TX_OK -eq 5 ]]; then
  ok "Submitted 5/5 test transactions"
else
  fail "Only ${TX_OK}/5 transactions accepted (node may require auth — check API config)"
fi

# ---------------------------------------------------------------------------
# Step 3 — Wait for consensus to produce blocks
# ---------------------------------------------------------------------------
log "Step 3: Waiting ${CONSENSUS_WAIT}s for consensus to propagate blocks..."
sleep "$CONSENSUS_WAIT"

# ---------------------------------------------------------------------------
# Step 4 — Chain height: all nodes must have height > 0
# ---------------------------------------------------------------------------
log "Step 4: Verifying chain height > 0 on all nodes..."
HEIGHT_OK=0
for port in "${PORTS[@]}"; do
  HEIGHT=$(curl -sf --max-time 5 "http://localhost:${port}/api/v1/chain/height" 2>/dev/null \
           | grep -oP '"height"\s*:\s*\K[0-9]+' || echo "0")
  if [[ "$HEIGHT" -gt 0 ]]; then
    ((HEIGHT_OK++))
  else
    fail "Port ${port} reported height=${HEIGHT}"
  fi
done

if [[ $HEIGHT_OK -eq ${#PORTS[@]} ]]; then
  ok "All ${#PORTS[@]} nodes have chain height > 0"
else
  fail "Only ${HEIGHT_OK}/${#PORTS[@]} nodes have height > 0"
fi

# ---------------------------------------------------------------------------
# Step 5 — Tip consensus: all 20 nodes must agree on the same tip hash
# ---------------------------------------------------------------------------
log "Step 5: Verifying all nodes agree on the same chain tip..."
declare -A TIP_COUNTS
for port in "${PORTS[@]}"; do
  TIP=$(curl -sf --max-time 5 "http://localhost:${port}/api/v1/chain/tip" 2>/dev/null \
        | grep -oP '"hash"\s*:\s*"\K[^"]+' || echo "UNKNOWN_${port}")
  TIP_COUNTS["$TIP"]=$(( ${TIP_COUNTS["$TIP"]:-0} + 1 ))
done

UNIQUE_TIPS=${#TIP_COUNTS[@]}
MAJORITY_TIP=$(for k in "${!TIP_COUNTS[@]}"; do echo "${TIP_COUNTS[$k]} $k"; done | sort -rn | head -1 | awk '{print $2}')
MAJORITY_COUNT=${TIP_COUNTS[$MAJORITY_TIP]:-0}

if [[ "$UNIQUE_TIPS" -eq 1 ]]; then
  ok "All ${#PORTS[@]} nodes agree on tip: ${MAJORITY_TIP:0:16}..."
else
  fail "Tip divergence! ${UNIQUE_TIPS} unique tips. Majority (${MAJORITY_COUNT}/${#PORTS[@]}): ${MAJORITY_TIP:0:16}..."
fi

# ---------------------------------------------------------------------------
# Step 6 — AI endpoint smoke tests
# ---------------------------------------------------------------------------
log "Step 6: Smoke-testing AI endpoints on node-seed..."

# Reputation endpoint
REP_STATUS=$(curl -sf --max-time 5 -o /dev/null -w "%{http_code}" \
  http://localhost:8000/api/v1/consensus/reputation 2>/dev/null || echo "000")
[[ "$REP_STATUS" == "200" ]] && ok "GET /consensus/reputation → 200" \
                              || fail "GET /consensus/reputation → ${REP_STATUS}"

# Fee prediction endpoint
FEE_STATUS=$(curl -sf --max-time 5 -o /dev/null -w "%{http_code}" \
  "http://localhost:8000/api/v1/ai/fee-prediction?txCount=50" 2>/dev/null || echo "000")
[[ "$FEE_STATUS" == "200" ]] && ok "GET /ai/fee-prediction?txCount=50 → 200" \
                              || fail "GET /ai/fee-prediction?txCount=50 → ${FEE_STATUS}"

# Federated model endpoint
FED_STATUS=$(curl -sf --max-time 5 -o /dev/null -w "%{http_code}" \
  http://localhost:8000/api/v1/ai/federated/model 2>/dev/null || echo "000")
[[ "$FED_STATUS" == "200" ]] && ok "GET /ai/federated/model → 200" \
                              || fail "GET /ai/federated/model → ${FED_STATUS}"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "════════════════════════════════════════════════════════════════"
printf "  %-8s %d/%d checks passed\n" "RESULT:" "$PASS" "$(( PASS + FAIL ))"
echo "════════════════════════════════════════════════════════════════"

# ---------------------------------------------------------------------------
# Teardown
# ---------------------------------------------------------------------------
if [[ "$KEEP_UP" == "false" ]]; then
  log "Tearing down cluster..."
  docker compose -f "$COMPOSE_FILE" down -v
  log "Cluster removed."
else
  log "--keep flag set. Cluster left running for inspection."
fi

[[ $FAIL -eq 0 ]] && exit 0 || exit 1
