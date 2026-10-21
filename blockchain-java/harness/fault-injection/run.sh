#!/usr/bin/env bash
# [O-2] Runs the fault-injection scenarios against an N-validator network on THIS host.
#   harness/fault-injection/run.sh 4 [scenario...]     scenarios: baseline leader-crash partition
#                                                      delay-loss restart-resync equivocation clock-skew
# Results: harness/fault-injection/results/<UTC>-N<N>.jsonl (+ .md summary).
# Every result is from a SINGLE-HOST Docker network (simulated). It is NOT a multi-machine test.
set -euo pipefail
N="${1:?usage: run.sh <4|7> [scenario...]}"; shift || true
SCENARIOS=("$@"); [[ ${#SCENARIOS[@]} -gt 0 ]] || SCENARIOS=(baseline leader-crash partition delay-loss restart-resync equivocation clock-skew)
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
ENV="$HERE/.env.$N"; COMPOSE="$HERE/docker-compose.$N.yml"; ADDR="$HERE/.addresses.$N"
BLOCK_S=60            # Config.TARGET_BLOCK_TIME_MS (protocol constant, not configurable)
PY="$(command -v python3 || command -v python || command -v py)"
mkdir -p "$HERE/results"; OUT="$HERE/results/$(date -u +%Y%m%dT%H%M%SZ)-N$N.jsonl"
DC=(docker compose --env-file "$ENV" -f "$COMPOSE")
PUMBA=(docker run --rm -v //var/run/docker.sock:/var/run/docker.sock gaiaadm/pumba)
url() { echo "http://localhost:$((18080 + $1))"; }
all_urls() { local s=""; for i in $(seq 1 "$N"); do s+="$(url "$i"),"; done; echo "${s%,}"; }
ip() { echo "172.28.0.$((10 + $1))"; }
check() { "$PY" "$HERE/check.py" --nodes "$(all_urls)" --out "$OUT" "$@"; }
container_of() { awk -v a="$1" '$2==a {print $1}' "$ADDR"; }
leader() { curl -s "$(url 1)/api/v1/consensus" | "$PY" -c 'import json,sys; print(json.load(sys.stdin)["currentLeader"])'; }
key_of() { grep "^V$1_KEY=" "$ENV" | cut -d= -f2; }
wait_up() { for i in $(seq 1 "$N"); do for _ in $(seq 1 60); do curl -sf "$(url "$i")/api/v1/health" >/dev/null && break; sleep 5; done; done; }

[[ -f "$ENV" ]] || "$HERE/gen.sh" "$N"
( cd "$ROOT" && mvn -q -o -DskipTests package && docker build -q -t hybridchain-node:local -f Dockerfile . \
  && docker build -q -t hybridchain-harness:local -f harness/fault-injection/Dockerfile.harness harness/fault-injection )
"${DC[@]}" up -d; wait_up
echo "{\"environment\": \"single-host Docker network, $N validators, simulated (NOT multi-machine)\"}" >> "$OUT"

for sc in "${SCENARIOS[@]}"; do
  echo "=== $sc"
  case "$sc" in
    baseline)
      check --phase baseline --expect-progress 2 --timeout $((BLOCK_S * 5)) ;;
    leader-crash)
      L="$(container_of "$(leader)")"; echo "killing leader $L"; docker kill "$L" >/dev/null
      check --phase leader-crash/survivors --expect-progress 2 --timeout $((BLOCK_S * 8)) || true
      docker start "$L" >/dev/null; wait_up
      check --phase leader-crash/rejoined --expect-progress 1 --timeout $((BLOCK_S * 6)) ;;
    partition)
      HALF=$((N / 2)); A=(); B=(); for i in $(seq 1 "$N"); do if [[ $i -le $HALF ]]; then A+=("$i"); else B+=("$i"); fi; done
      tA=(); for i in "${B[@]}"; do tA+=(--target "$(ip "$i")"); done
      tB=(); for i in "${A[@]}"; do tB+=(--target "$(ip "$i")"); done
      D=$((BLOCK_S * 4))
      "${PUMBA[@]}" netem --duration "${D}s" --tc-image gaiadocker/iproute2 loss --percent 100 "${tA[@]}" $(for i in "${A[@]}"; do echo "hc-v$i"; done) &
      "${PUMBA[@]}" netem --duration "${D}s" --tc-image gaiadocker/iproute2 loss --percent 100 "${tB[@]}" $(for i in "${B[@]}"; do echo "hc-v$i"; done) &
      sleep $((D / 2)); check --phase partition/during || true   # safety must hold; progress is not expected
      wait; check --phase partition/healed --expect-progress 2 --timeout $((BLOCK_S * 8)) ;;
    delay-loss)
      D=$((BLOCK_S * 5))
      "${PUMBA[@]}" netem --duration "${D}s" --tc-image gaiadocker/iproute2 delay --time 300 --jitter 100 $(for i in $(seq 1 "$N"); do echo "hc-v$i"; done) &
      "${PUMBA[@]}" netem --duration "${D}s" --tc-image gaiadocker/iproute2 loss --percent 10 $(for i in $(seq 1 "$N"); do echo "hc-v$i"; done) &
      check --phase delay-loss/during --expect-progress 1 --timeout "$D" || true
      wait; check --phase delay-loss/after --expect-progress 1 --timeout $((BLOCK_S * 5)) ;;
    restart-resync)
      docker stop "hc-v$N" >/dev/null; sleep $((BLOCK_S * 3)); docker start "hc-v$N" >/dev/null; wait_up
      check --phase restart-resync --expect-progress 1 --timeout $((BLOCK_S * 8)) ;;
    equivocation)
      ( cd "$ROOT" && SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac
        java -cp "target/test-classes${SEP}target/classes${SEP}$(cat cp.txt)" com.hybrid.blockchain.harness.EquivocationEvidenceTool \
          "$(key_of "$N")" "$(key_of 1)" "$(url 1)" "coap://localhost:$((15683 + 1))/tx" ) | tee -a "$OUT.log"
      CHEATER="$(awk -v c="hc-v$N" '$1==c {print $2}' "$ADDR")"
      sleep $((BLOCK_S * 3)); check --phase equivocation/jail --expect-jailed "$CHEATER" --expect-progress 1 --timeout $((BLOCK_S * 6)) ;;
    clock-skew)
      export "V${N}_FAKETIME=+30s" "V${N}_PRELOAD=/usr/lib/x86_64-linux-gnu/faketime/libfaketime.so.1"
      "${DC[@]}" up -d "v$N"; wait_up
      check --phase clock-skew/during --expect-progress 2 --timeout $((BLOCK_S * 8)) || true
      unset "V${N}_FAKETIME" "V${N}_PRELOAD"; "${DC[@]}" up -d "v$N"; wait_up
      check --phase clock-skew/after --expect-progress 1 --timeout $((BLOCK_S * 5)) ;;
    *) echo "unknown scenario $sc" >&2; exit 2 ;;
  esac
done

"$PY" - "$OUT" > "${OUT%.jsonl}.md" <<'PYEOF'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
print("# Fault-injection results\n")
print("**Environment:** " + rows[0].get("environment", "?") + "\n")
print("| Phase | Result | Safety | Liveness | Jail agreement |\n|---|---|---|---|---|")
for r in rows[1:]:
    g = lambda k: ("ok" if r[k]["ok"] else "FAIL") if k in r else "n/a"
    print(f"| {r['phase']} | {'PASS' if r['ok'] else 'FAIL'} | {g('safety')} | {g('liveness')} | {g('jail')} |")
PYEOF
echo "results: $OUT and ${OUT%.jsonl}.md"
echo "stop the network with: ${DC[*]} down -v"
