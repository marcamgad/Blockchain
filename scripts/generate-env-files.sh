#!/usr/bin/env bash
# Reads scripts/keys.env and writes 20 individual env files:
# envs/node1.env, envs/node2.env, ..., envs/node20.env
# Each file contains everything a single node needs to start.

set -euo pipefail

source scripts/keys.env

mkdir -p envs

# One shared AES key for encrypted LevelDB storage — never changes per cluster
# In production this would be in a secrets manager. For testing, deterministic is fine.
SHARED_AES_KEY="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

# Network ID — all nodes must share this or they reject each other's transactions
NETWORK_ID="1337"

for i in $(seq 1 20); do
  # Read this node's keys
  PRIV_VAR="NODE_${i}_PRIVATE_KEY"
  PRIV="${!PRIV_VAR}"

  # Node 1 is the seed (bootstrap) node
  IS_SEED="false"
  SEED_PEER="node1:6001"
  if [ "$i" -eq 1 ]; then
    IS_SEED="true"
    SEED_PEER=""
  fi

  # Node role: nodes 1-4 are validators (PBFT quorum needs 4 for f=1 tolerance)
  # nodes 5-10 are observers, nodes 11-15 are gateways, nodes 16-20 are light nodes
  if   [ "$i" -le 4  ]; then ROLE="VALIDATOR"
  elif [ "$i" -le 10 ]; then ROLE="OBSERVER"
  elif [ "$i" -le 15 ]; then ROLE="GATEWAY"
  else                        ROLE="LIGHT"
  fi

  cat > "envs/node${i}.env" <<EOF
# HybridChain Node ${i} environment
NODE_PRIVATE_KEY=${PRIV}
VALIDATOR_PUBKEYS=${VALIDATOR_PUBKEYS}
STORAGE_AES_KEY=${SHARED_AES_KEY}
STORAGE_PATH=/app/data
NETWORK_ID=${NETWORK_ID}
NODE_ID=node${i}
NODE_NAME=HybridChain-Node-${i}
NODE_ROLE=${ROLE}
P2P_PORT=6001
API_PORT=8000
COAP_PORT=5683
IS_SEED=${IS_SEED}
SEED_PEER=${SEED_PEER}
LOG_LEVEL=INFO
MEMPOOL_LIMIT=10000
MAX_BLOCKS=0
EOF

  echo "Written envs/node${i}.env (role=${ROLE})"
done

echo "All 20 env files ready in envs/"
