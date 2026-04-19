#!/usr/bin/env bash
# One command to build, start, test, and report.

set -euo pipefail

echo "Step 1/5 — Building JAR..."
cd blockchain-java && mvn package -DskipTests -q && cd ..

echo "Step 2/5 — Generating cryptographic keys..."
bash scripts/generate-keys.sh

echo "Step 3/5 — Generating per-node env files..."
bash scripts/generate-env-files.sh

echo "Step 4/5 — Building Docker image..."
docker build -t hybridchain:latest blockchain-java/

echo "Step 5/5 — Starting cluster and running tests..."
bash scripts/start-cluster.sh
bash scripts/run-tests.sh
TEST_EXIT=$?

if [ "$TEST_EXIT" -ne 0 ]; then
  echo ""
  echo "Tests failed. Collecting logs from all nodes..."
  mkdir -p test-logs
  for i in $(seq 1 20); do
    docker logs hybridchain-node${i} > "test-logs/node${i}.log" 2>&1 || true
  done
  echo "Logs saved to test-logs/"
fi

exit $TEST_EXIT
