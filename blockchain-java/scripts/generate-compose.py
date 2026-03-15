#!/usr/bin/env python3
import sys
import os

def generate_compose(n_nodes):
    header = """version: '3.8'

networks:
  hb-net:
    driver: bridge

x-node-common: &node-common
  build: .
  networks:
    - hb-net
  environment:
    - STORAGE_AES_KEY=${STORAGE_AES_KEY:-00112233445566778899aabbccddeeff}
    - NETWORK_ID=101
    - TARGET_BLOCK_TIME_MS=5000
    - VALIDATOR_PUBKEYS=${VALIDATOR_PUBKEYS}
"""

    services = "services:\n"
    
    for i in range(1, n_nodes + 1):
        is_seed = "true" if i == 1 else "false"
        seed_peer = "hb-node-1:6001" if i > 1 else ""
        
        services += f"""
  hb-node-{i}:
    <<: *node-common
    container_name: hb-node-{i}
    hostname: hb-node-{i}
    ports:
      - "{8000+i}:8000"
      - "{6000+i}:6001"
    environment:
      - NODE_ID=hb-node-{i}
      - IS_SEED={is_seed}
      - SEED_PEER={seed_peer}
      - NODE_PRIVATE_KEY=${{NODE_{i}_PRIVATE_KEY}}
      - SERVER_PORT=8000
      - P2P_PORT=6001
"""

    return header + services

if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 20
    print(generate_compose(n))
