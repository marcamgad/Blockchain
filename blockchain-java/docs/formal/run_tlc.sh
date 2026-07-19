#!/usr/bin/env bash
# Reproduce the Phase 1 step 4 model-checking results from a clean checkout.
#
#   ./run_tlc.sh
#
# Downloads tla2tools.jar (official TLA+ release) if not already present, then runs
# TLC over all four configurations and prints a summary.
#
# Verified with: TLC2 version 2.19 of 08 August 2024 (rev 5a47802), JDK 17.
# tla2tools.jar SHA-256 at time of verification:
#   936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88
#
# TLC exit codes: 0 = no error found; 12 = invariant violated (EXPECTED for Broken).

set -uo pipefail
cd "$(dirname "$0")"

JAR="${TLA_TOOLS_JAR:-./tla2tools.jar}"
URL="https://github.com/tlaplus/tlaplus/releases/latest/download/tla2tools.jar"

if [[ ! -f "$JAR" ]]; then
  echo "==> tla2tools.jar not found; downloading from $URL"
  curl -sL --max-time 300 -o "$JAR" "$URL" || { echo "download failed"; exit 1; }
fi
echo "==> using $JAR"
command -v sha256sum >/dev/null && sha256sum "$JAR"

run() {
  local cfg="$1" expected="$2"
  echo ""
  echo "============================================================"
  echo "  $cfg   (expected: $expected)"
  echo "============================================================"
  java -XX:+UseParallelGC -cp "$JAR" tlc2.TLC -nowarning \
       -config "$cfg" LeaderSelect.tla 2>&1 \
    | grep -E "No error has been found|Invariant .* is violated|distinct states found|Deadlock"
  echo "  -> TLC exit code: ${PIPESTATUS[0]}"
}

run LeaderSelect_Broken.cfg     "VIOLATION (exit 12)"
run LeaderSelect_Broken_N7.cfg  "VIOLATION (exit 12)"
run LeaderSelect_Fixed.cfg      "no error (exit 0), 35 distinct states"
run LeaderSelect_Fixed_N7.cfg   "no error (exit 0), 330 distinct states"

echo ""
echo "============================================================"
echo "Independent cross-check (does NOT use TLC):"
echo "  mvn -o test -Dtest=LeaderSelectModelCheckTest"
echo "Expect the same verdicts and matching Fixed state counts (35 / 330)."
echo "============================================================"
