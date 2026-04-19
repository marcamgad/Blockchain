#!/usr/bin/env bash
# Generates 20 real secp256k1 key pairs using KeygenTool.
# Output: scripts/keys.env — one line per node: NODE_N_PRIVATE_KEY, NODE_N_PUBLIC_KEY, NODE_N_ADDRESS
# Also writes VALIDATOR_PUBKEYS= (comma-separated all 20 public keys)
# Never commits this file — add it to .gitignore after generation.

set -euo pipefail

# Find the built JAR
JAR=$(ls blockchain-java/target/blockchain-java-*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
  echo "ERROR: JAR not found. Run 'mvn package -DskipTests' inside blockchain-java/ first."
  exit 1
fi

echo "Generating 20 key pairs..."
# We use the Spring Boot JAR with the correct classpath inside it
OUTPUT=$(java -cp "$JAR" -Dloader.main=com.hybrid.blockchain.tools.KeygenTool org.springframework.boot.loader.PropertiesLauncher 20 || java -cp "$JAR" com.hybrid.blockchain.tools.KeygenTool 20)

KEYS_FILE="scripts/keys.env"
> "$KEYS_FILE"

PUBKEYS=""

for i in $(seq 1 20); do
  # Grep only for the clean key=value lines
  PRIV=$(echo "$OUTPUT"  | grep "NODE_${i}_PRIVATE_KEY=" | cut -d= -f2)
  PUB=$(echo  "$OUTPUT"  | grep "NODE_${i}_PUBLIC_KEY="  | cut -d= -f2)
  ADDR=$(echo "$OUTPUT"  | grep "NODE_${i}_ADDRESS="     | cut -d= -f2)

  echo "NODE_${i}_PRIVATE_KEY=${PRIV}" >> "$KEYS_FILE"
  echo "NODE_${i}_PUBLIC_KEY=${PUB}"   >> "$KEYS_FILE"
  echo "NODE_${i}_ADDRESS=${ADDR}"     >> "$KEYS_FILE"

  if [ -z "$PUBKEYS" ]; then
    PUBKEYS="$PUB"
  else
    PUBKEYS="${PUBKEYS},${PUB}"
  fi
done

echo "VALIDATOR_PUBKEYS=${PUBKEYS}" >> "$KEYS_FILE"

echo "Keys written to $KEYS_FILE"
echo "All 20 nodes use the same VALIDATOR_PUBKEYS for quorum"
