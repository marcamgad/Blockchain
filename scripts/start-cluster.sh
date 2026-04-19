#!/usr/bin/env bash
# Starts all 20 nodes and waits until every single one passes its health check.
# Fails loudly if any node does not become healthy within 5 minutes.

set -euo pipefail

COMPOSE_FILE="docker-compose.yml"
TIMEOUT_SECONDS=300
POLL_INTERVAL=5

echo "============================================"
echo " HybridChain 20-node cluster startup"
echo "============================================"

# Bring up all 20 nodes
docker compose -f "$COMPOSE_FILE" up -d

echo "Waiting for all 20 nodes to become healthy..."
echo "(Timeout: ${TIMEOUT_SECONDS}s)"

START_TIME=$(date +%s)

while true; do
  ALL_HEALTHY=true
  UNHEALTHY_NODES=""

  for i in $(seq 1 20); do
    STATUS=$(docker inspect --format='{{.State.Health.Status}}' \
              "hybridchain-node${i}" 2>/dev/null || echo "missing")
    if [ "$STATUS" != "healthy" ]; then
      ALL_HEALTHY=false
      UNHEALTHY_NODES="${UNHEALTHY_NODES} node${i}(${STATUS})"
    fi
  done

  if [ "$ALL_HEALTHY" = true ]; then
    echo ""
    echo "✓ All 20 nodes are healthy!"
    break
  fi

  ELAPSED=$(( $(date +%s) - START_TIME ))
  if [ "$ELAPSED" -gt "$TIMEOUT_SECONDS" ]; then
    echo ""
    echo "FATAL: Timed out after ${TIMEOUT_SECONDS}s. Unhealthy nodes:${UNHEALTHY_NODES}"
    echo "Run: docker compose logs --tail=50 to diagnose"
    exit 1
  fi

  printf "\r  [%3ds] Waiting...%s              " "$ELAPSED" "$UNHEALTHY_NODES"
  sleep "$POLL_INTERVAL"
done

echo ""
echo "Cluster endpoints:"
for i in $(seq 1 20); do
  PORT=$((8000 + i))
  ROLE="observer"
  [ "$i" -le 4  ] && ROLE="validator"
  [ "$i" -gt 10 ] && [ "$i" -le 15 ] && ROLE="gateway"
  [ "$i" -gt 15 ] && ROLE="light"
  echo "  node${i} (${ROLE}): http://localhost:${PORT}/api/v1/health"
done
