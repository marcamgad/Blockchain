#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PORTS=()
for p in $(seq 8000 8019); do
  PORTS+=("$p")
done

PASS_COUNT=0
FAIL_COUNT=0

log() { echo "[$(date +%H:%M:%S)] $*"; }
pass() { echo "PASS - $1"; PASS_COUNT=$((PASS_COUNT + 1)); }
fail() { echo "FAIL - $1"; FAIL_COUNT=$((FAIL_COUNT + 1)); }

json_field() {
  local payload="$1"
  local field="$2"
  echo "$payload" | jq -r "$field" 2>/dev/null
}

wait_all_healthy() {
  local timeout_seconds="$1"
  local start
  start=$(date +%s)

  while true; do
    local ok=0
    for port in "${PORTS[@]}"; do
      if curl -fsS "http://localhost:${port}/api/v1/health" >/dev/null 2>&1; then
        ok=$((ok + 1))
      fi
    done

    if [[ "$ok" -eq 20 ]]; then
      return 0
    fi

    if (( $(date +%s) - start >= timeout_seconds )); then
      return 1
    fi
    sleep 2
  done
}

same_height() {
  local first_height=""
  for port in "${PORTS[@]}"; do
    local payload
    payload=$(curl -fsS "http://localhost:${port}/api/v1/chain/height" 2>/dev/null || true)
    local h
    h=$(json_field "$payload" '.height')
    if [[ -z "$h" || "$h" == "null" ]]; then
      return 1
    fi
    if [[ -z "$first_height" ]]; then
      first_height="$h"
    elif [[ "$h" != "$first_height" ]]; then
      return 1
    fi
  done
  return 0
}

log "Scenario 1: Liveness"
if wait_all_healthy 60; then
  pass "Liveness: all 20 nodes healthy within 60s"
else
  fail "Liveness: not all nodes became healthy within 60s"
fi

log "Scenario 2: Sync"
sleep 30
if same_height; then
  pass "Sync: all 20 nodes report the same chain height"
else
  fail "Sync: chain heights diverged across nodes"
fi

log "Scenario 3: Transaction propagation"
TX_RESPONSE=$(curl -fsS -X POST "http://localhost:8001/api/v1/transactions/submit" \
  -H "Content-Type: application/json" \
  -d '{"type":"ACCOUNT","to":"hb_tx_sink_001","amount":1,"fee":0}' 2>/dev/null || true)
TX_ID=$(json_field "$TX_RESPONSE" '.txId')
if [[ -n "$TX_ID" && "$TX_ID" != "null" ]]; then
  sleep 15
  seen=0
  for port in $(seq 8000 8019); do
    if [[ "$port" == "8001" ]]; then
      continue
    fi
    if curl -fsS "http://localhost:${port}/api/v1/transactions/${TX_ID}" >/dev/null 2>&1; then
      seen=$((seen + 1))
    fi
  done
  if [[ "$seen" -ge 15 ]]; then
    pass "Transaction propagation: tx observed on at least 15 other nodes"
  else
    fail "Transaction propagation: tx observed on only ${seen} other nodes"
  fi
else
  fail "Transaction propagation: failed to submit seed transaction"
fi

log "Scenario 4: IoT lifecycle sequence"
make_iot_tx() {
  local action="$1"
  local extra="$2"
  local payload
  payload=$(printf '{"action":"%s"%s}' "$action" "$extra" | xxd -p -c 9999)
  curl -fsS -X POST "http://localhost:8001/api/v1/transactions/submit" \
    -H "Content-Type: application/json" \
    -d "{\"type\":\"IOT_MANAGEMENT\",\"to\":\"iot-mgr\",\"amount\":0,\"fee\":0,\"data\":\"${payload}\"}" 2>/dev/null || true
}

IOT1=$(make_iot_tx "PROVISION" ',"deviceId":"iot-dev-001","manufacturer":"mfgr-1","model":"m1","devicePublicKey":"02aa","manufacturerSignature":"aa"')
IOT2=$(make_iot_tx "ACTIVATE" ',"deviceId":"iot-dev-001","owner":"hb_owner_001","devicePublicKey":"02aa"')
IOT3=$(make_iot_tx "SUSPEND" ',"deviceId":"iot-dev-001","reason":"test"')
if [[ -n "$(json_field "$IOT1" '.txId')" && -n "$(json_field "$IOT2" '.txId')" && -n "$(json_field "$IOT3" '.txId')" ]]; then
  sleep 20
  ok=0
  for port in 8001 8002 8003 8004; do
    status=$(curl -fsS "http://localhost:${port}/api/v1/iot/devices/iot-dev-001" 2>/dev/null | jq -r '.status' 2>/dev/null || true)
    if [[ "$status" == "SUSPENDED" ]]; then
      ok=$((ok + 1))
    fi
  done
  if [[ "$ok" -eq 4 ]]; then
    pass "IoT lifecycle: SUSPENDED state visible on all validators"
  else
    fail "IoT lifecycle: state not synchronized across validators"
  fi
else
  fail "IoT lifecycle: failed to submit lifecycle transactions"
fi

log "Scenario 5: Consensus fault tolerance"
HEIGHT_BEFORE=$(curl -fsS "http://localhost:8001/api/v1/chain/height" | jq -r '.height' 2>/dev/null || echo "0")
docker stop validator-4 >/dev/null 2>&1 || true
for i in 1 2 3; do
  curl -fsS -X POST "http://localhost:8001/api/v1/transactions/submit" \
    -H "Content-Type: application/json" \
    -d "{\"type\":\"ACCOUNT\",\"to\":\"hb_fault_tolerance_${i}\",\"amount\":1,\"fee\":0}" >/dev/null 2>&1 || true
done
sleep 20
HEIGHT_AFTER=$(curl -fsS "http://localhost:8001/api/v1/chain/height" | jq -r '.height' 2>/dev/null || echo "0")
if [[ "$HEIGHT_AFTER" -gt "$HEIGHT_BEFORE" ]]; then
  pass "Consensus fault tolerance: chain advanced with one validator down"
else
  fail "Consensus fault tolerance: chain did not advance after validator stop"
fi

log "Scenario 6: Node recovery"
docker start validator-4 >/dev/null 2>&1 || true
sleep 30
H_V4=$(curl -fsS "http://localhost:8004/api/v1/chain/height" | jq -r '.height' 2>/dev/null || echo "-1")
H_REF=$(curl -fsS "http://localhost:8001/api/v1/chain/height" | jq -r '.height' 2>/dev/null || echo "-2")
if [[ "$H_V4" == "$H_REF" ]]; then
  pass "Node recovery: validator-4 caught up to chain height"
else
  fail "Node recovery: validator-4 did not catch up"
fi

log "Scenario 7: Rate limiting"
HTTP_429=0
for _ in $(seq 1 50); do
  status=$(curl -s -o /dev/null -w "%{http_code}" -X GET "http://localhost:8005/api/v1/health" || true)
  if [[ "$status" == "429" ]]; then
    HTTP_429=$((HTTP_429 + 1))
  fi
done
if [[ "$HTTP_429" -gt 0 ]]; then
  pass "Rate limiting: observed HTTP 429 under burst load"
else
  fail "Rate limiting: no HTTP 429 observed in burst test"
fi

log "Scenario 8: Pruning under load"
START_SIZE=$(docker exec node-15 sh -c 'du -sb /app/data 2>/dev/null | awk "{print \$1}"' 2>/dev/null || echo "0")
for i in $(seq 1 60); do
  curl -fsS -X POST "http://localhost:8001/api/v1/transactions/submit" \
    -H "Content-Type: application/json" \
    -d "{\"type\":\"ACCOUNT\",\"to\":\"hb_prune_${i}\",\"amount\":1,\"fee\":0}" >/dev/null 2>&1 || true
done
sleep 40
END_SIZE=$(docker exec node-15 sh -c 'du -sb /app/data 2>/dev/null | awk "{print \$1}"' 2>/dev/null || echo "0")
if curl -fsS "http://localhost:8019/api/v1/health" >/dev/null 2>&1; then
  if [[ "$END_SIZE" -lt $((START_SIZE + 200000000)) ]]; then
    pass "Pruning under load: node-15 healthy and storage growth bounded"
  else
    fail "Pruning under load: storage growth appears unbounded"
  fi
else
  fail "Pruning under load: node-15 health check failed"
fi

echo ""
echo "Summary: PASS=${PASS_COUNT} FAIL=${FAIL_COUNT}"
if [[ "$FAIL_COUNT" -gt 0 ]]; then
  exit 1
fi
