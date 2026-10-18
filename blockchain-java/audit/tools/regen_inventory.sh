#!/usr/bin/env bash
# Regenerate the whole Phase A inventory from source. Run from blockchain-java/.
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p audit/out audit/inventory
mvn -o -q dependency:build-classpath -Dmdep.outputFile=audit/out/cp-test.txt -Dmdep.includeScope=test
mvn -o -q dependency:list -DincludeScope=runtime -DoutputFile=audit/out/deps-runtime.txt
java -Xmx2g audit/tools/Inventory.java .
if command -v py >/dev/null 2>&1; then PY="py -I"; else PY="python3 -I"; fi
$PY audit/tools/deps.py
$PY audit/tools/ledger.py build
