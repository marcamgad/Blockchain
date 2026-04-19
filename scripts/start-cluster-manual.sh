#!/usr/bin/env bash
# Manual 20-node cluster starter (bypass docker-compose)
# Replicates the logic of docker-compose.yml exactly.

set -euo pipefail

IMAGE="hybridchain:latest"
NETWORK="hybridchain_net"

echo "Creating network: $NETWORK"
docker network create "$NETWORK" 2>/dev/null || true

echo "Starting 20 HybridChain nodes..."

for i in $(seq 1 20); do
  NAME="hybridchain-node${i}"
  API_PORT=$((8000 + i))
  P2P_PORT=$((6000 + i))
  ENV_FILE="envs/node${i}.env"

  echo "  Starting $NAME (API:$API_PORT, P2P:$P2P_PORT)"
  
  # Standard node start
  docker run -d \
    --name "$NAME" \
    --hostname "node${i}" \
    --network "$NETWORK" \
    --env-file "$ENV_FILE" \
    -p "${API_PORT}:8000" \
    -p "${P2P_PORT}:6001" \
    -v "${NAME}-data:/app/data" \
    -v "${NAME}-logs:/app/logs" \
    --restart unless-stopped \
    "$IMAGE"
done

echo "20 nodes initialized. Waiting 30s for JVM startup..."
sleep 30
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep hybridchain
