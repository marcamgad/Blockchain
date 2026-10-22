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
# first interpreter that actually runs (on Windows, "python3" can be the Microsoft Store stub)
PY=""; for c in py python3 python; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c "import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)" >/dev/null 2>&1; then PY="$c"; break; fi
done
[[ -n "$PY" ]] || { echo "Python 3.8+ is required (py, python3 or python)" >&2; exit 1; }
mkdir -p "$HERE/results"; OUT="$HERE/results/$(date -u +%Y%m%dT%H%M%SZ)-N$N.jsonl"
DC=(docker compose --env-file "$ENV" -f "$COMPOSE")
PUMBA=(docker run --rm -v //var/run/docker.sock:/var/run/docker.sock gaiaadm/pumba)
url() { echo "http://localhost:$((18080 + $1))"; }
all_urls() { local s=""; for i in $(seq 1 "$N"); do s+="$(url "$i"),"; done; echo "${s%,}"; }
ip() { echo "172.28.0.$((10 + $1))"; }
FAILS=0   # a failed phase is recorded and the run continues with the next scenario
check() {
  if ! "$PY" "$HERE/check.py" --nodes "$(all_urls)" --out "$OUT" "$@"; then
    FAILS=$((FAILS + 1))
    # keep the evidence: last log lines of every node at the moment the phase failed
    for i in $(seq 1 "$N"); do timeout 20 docker logs --tail 200 "hc-v$i" > "${OUT%.jsonl}.fail$FAILS.v$i.log" 2>&1 || true; done
  fi
}
container_of() { awk -v a="$1" '$2==a {print $1}' "$ADDR"; }
leader() { curl -s "$(url 1)/api/v1/consensus" | "$PY" -c 'import json,sys; print(json.load(sys.stdin)["currentLeader"])'; }
key_of() { grep "^V$1_KEY=" "$ENV" | cut -d= -f2; }
wait_up() { for i in $(seq 1 "$N"); do for _ in $(seq 1 60); do curl -sf "$(url "$i")/api/v1/health" >/dev/null && break; sleep 5; done; done; }

"$HERE/gen.sh" "$N"   # keys only the first time; the compose file every time
echo "[1/4] building the node jar";        ( cd "$ROOT" && mvn -q -o -DskipTests package )
echo "[2/4] building hybridchain-node";    ( cd "$ROOT" && docker build -t hybridchain-node:local -f Dockerfile . )
echo "[3/4] building hybridchain-harness"; docker build -t hybridchain-harness:local -f "$HERE/Dockerfile.harness" "$HERE"
# networks of other sizes share the subnet, container names and host ports: stop them first
for other in "$HERE"/docker-compose.*.yml; do
  M="${other##*/docker-compose.}"; M="${M%.yml}"
  [[ "$M" != "$N" && -f "$HERE/.env.$M" ]] && docker compose --env-file "$HERE/.env.$M" -f "$other" down >/dev/null 2>&1 || true
done
# every run starts from genesis: remove this network's own data volumes (harness data only)
"${DC[@]}" down -v >/dev/null 2>&1 || true
echo "[4/4] starting $N validators";       "${DC[@]}" up -d --force-recreate; wait_up
for i in $(seq 1 "$N"); do
  curl -sf "$(url "$i")/api/v1/health" >/dev/null || { echo "node v$i did not start; last log lines:" >&2; docker logs --tail 20 "hc-v$i" >&2; exit 1; }
done
echo "all $N validators answer on the REST API"
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
      # --target is a flag of "netem" itself (not of the "loss" sub-command)
      "${PUMBA[@]}" netem --duration "${D}s" "${tA[@]}" loss --percent 100 $(for i in "${A[@]}"; do echo "hc-v$i"; done) &
      "${PUMBA[@]}" netem --duration "${D}s" "${tB[@]}" loss --percent 100 $(for i in "${B[@]}"; do echo "hc-v$i"; done) &
      sleep $((D / 2)); check --phase partition/during || true   # safety must hold; progress is not expected
      wait; check --phase partition/healed --expect-progress 2 --timeout $((BLOCK_S * 8)) ;;
    delay-loss)
      # one netem qdisc per interface at a time: delay first, then loss (running both at once fails)
      D=$((BLOCK_S * 4)); NODES=$(for i in $(seq 1 "$N"); do echo "hc-v$i"; done)
      "${PUMBA[@]}" netem --duration "${D}s" delay --time 300 --jitter 100 $NODES &
      check --phase delay/during --expect-progress 1 --timeout "$D"
      wait
      "${PUMBA[@]}" netem --duration "${D}s" loss --percent 10 $NODES &
      check --phase loss/during --expect-progress 1 --timeout "$D"
      wait; check --phase delay-loss/after --expect-progress 1 --timeout $((BLOCK_S * 5)) ;;
    restart-resync)
      docker stop "hc-v$N" >/dev/null; sleep $((BLOCK_S * 3)); docker start "hc-v$N" >/dev/null; wait_up
      check --phase restart-resync --expect-progress 1 --timeout $((BLOCK_S * 8)) ;;
    equivocation)
      # the tool runs on the host, where Config's static checks need a storage key; it stores
      # nothing, so a throw-away random key and DEBUG mode are enough
      TOOL_AES="$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')"
      if ( cd "$ROOT" && SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac
        java -DDEBUG=true -DSTORAGE_AES_KEY="$TOOL_AES" -cp "target/test-classes${SEP}target/classes${SEP}$(cat cp.txt)" \
          com.hybrid.blockchain.harness.EquivocationEvidenceTool \
          "$(key_of "$N")" "$(key_of 1)" "$(url 1)" ) 2>&1 | tee -a "$OUT.log"; [[ ${PIPESTATUS[0]} -eq 0 ]]; then
        CHEATER="$(awk -v c="hc-v$N" '$1==c {print $2}' "$ADDR")"
        sleep $((BLOCK_S * 3)); check --phase equivocation/jail --expect-jailed "$CHEATER" --expect-progress 1 --timeout $((BLOCK_S * 6))
      else
        echo "equivocation tool failed; phase recorded as FAIL, continuing" >&2
        echo '{"phase": "equivocation/jail", "ok": false, "error": "evidence tool failed"}' >> "$OUT"; FAILS=$((FAILS + 1))
      fi ;;
    clock-skew)
      # the last validator's clock runs 30 s fast, then 30 s slow (MAX_TIMESTAMP_DRIFT is 15 s).
      # Each phase checks the skew reached the JVM, the network keeps committing, and the skewed
      # node keeps its chain (named volume) and stays within one block of the others.
      SN="$(url "$N")"
      for SKEW in 30 -30; do
        export "V${N}_FAKETIME=$( [[ $SKEW -gt 0 ]] && echo "+${SKEW}s" || echo "${SKEW}s" )" \
               "V${N}_PRELOAD=/usr/lib/x86_64-linux-gnu/faketime/libfaketime.so.1"
        "${DC[@]}" up -d "v$N"; wait_up
        check --phase "clock-skew/${SKEW}s" --expect-skew "$SN" --skew-seconds "$SKEW" \
              --expect-progress 2 --timeout $((BLOCK_S * 8)) --expect-synced "$SN" --max-lag 1
      done
      unset "V${N}_FAKETIME" "V${N}_PRELOAD"; "${DC[@]}" up -d "v$N"; wait_up
      check --phase clock-skew/after --expect-skew "$SN" --skew-seconds 0 \
            --expect-progress 1 --timeout $((BLOCK_S * 5)) --expect-synced "$SN" --max-lag 1 ;;
    *) echo "unknown scenario $sc" >&2; exit 2 ;;
  esac
done

"$PY" - "$OUT" > "${OUT%.jsonl}.md" <<'PYEOF'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
print("# Fault-injection results\n")
print("**Environment:** " + rows[0].get("environment", "?") + "\n")
print("| Phase | Result | Safety | Liveness | Jail agreement | Clock skew | Skewed node synced |\n|---|---|---|---|---|---|---|")
for r in rows[1:]:
    g = lambda k: ("ok" if r[k]["ok"] else "FAIL") if k in r else "n/a"
    print(f"| {r['phase']} | {'PASS' if r['ok'] else 'FAIL'} | {g('safety')} | {g('liveness')} | {g('jail')} | {g('skew')} | {g('synced')} |")
PYEOF
echo "results: $OUT and ${OUT%.jsonl}.md  (failed phases: $FAILS)"
echo "stop the network with: ${DC[*]} down -v"
exit $(( FAILS > 0 ? 1 : 0 ))
