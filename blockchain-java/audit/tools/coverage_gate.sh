#!/usr/bin/env bash
# HybridChain audit coverage gate. Exits non-zero while ANY ledger row is UNREVIEWED or invalid.
# Run from blockchain-java/:  bash audit/tools/coverage_gate.sh
set -u
cd "$(dirname "$0")/../.."
if command -v py >/dev/null 2>&1; then PY="py -I"; elif command -v python3 >/dev/null 2>&1; then PY="python3 -I"; else PY="python -I"; fi
$PY audit/tools/ledger.py gate
