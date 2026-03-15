# Local IoT Blockchain Security & Resilience Testing Toolkit

This toolkit is a **safe-by-default**, **local-first** validation harness for your IoT blockchain node.  
It is designed to exercise signature validation, nonce/replay protection, parser hardening, mempool behavior, and distributed-system resilience.

## Safety model

- Default target restrictions: loopback/private addresses only.
- Explicit opt-out required to test non-local hosts.
- Intended only for environments you own and explicitly authorize.

---

## Architecture (text diagram)

```text
                           +------------------------------+
                           | security_toolkit_runner.py   |
                           | CLI + scenario schedule      |
                           +---------------+--------------+
                                           |
                                           v
                           +------------------------------+
                           | ToolkitOrchestrator          |
                           | lifecycle + scenario timing  |
                           +-----+------------------+-----+
                                 |                  |
                                 v                  v
                      +------------------+    +------------------+
                      | Device Swarm     |    | MetricsCollector |
                      | asyncio clients  |    | TPS/latency/p95  |
                      +--+-----------+---+    | p99/rejection    |
                         |           |        | conn + node CPU  |
                         v           v        +------------------+
                 +-----------+   +----------------+
                 | Scenarios |   | Chaos Profile  |
                 | replay    |   | delay/drop/    |
                 | fuzz DID  |   | partition      |
                 +-----+-----+   +--------+-------+
                       \              /
                        \            /
                         v          v
                         +-------------------------+
                         | TLS P2P Node (target)   |
                         | gossip/mempool/consensus|
                         +-------------------------+
```

---

## Modules

- `scripts/security_toolkit/config.py`: configuration dataclasses.
- `scripts/security_toolkit/safeguards.py`: host and scale guardrails.
- `scripts/security_toolkit/crypto_utils.py`: secp256k1 device identities, DID rotation, tx signing.
- `scripts/security_toolkit/client.py`: per-device asyncio TLS client with intermittent connectivity and burst traffic.
- `scripts/security_toolkit/scenarios.py`: adversarial payload mutations and identity replay probes.
- `scripts/security_toolkit/chaos.py`: latency, packet-loss, and partition simulation.
- `scripts/security_toolkit/metrics.py`: live metrics and optional node process CPU/memory stats.
- `scripts/security_toolkit/orchestrator.py`: scenario lifecycle controller.
- `scripts/security_toolkit_runner.py`: command-line entrypoint.

---

## Supported scenario set

### Traffic/swarm
- `baseline`: normal telemetry traffic.
- `gossip_pressure`: high-propagation pressure pattern to evaluate dedup logic under load.

### Protocol validation and parser robustness
- `replay_validation`: resends prior messages to validate nonce/timestamp replay handling.
- `malformed_fuzz`: mutates schema and payload encoding to test parser hardening.
- `signature_spam`: injects invalid signatures to measure verification-path resilience.

### Identity / DID tests
- `did_spoof`: mismatched sender DID vs signed payload identity.
- `invalid_pubkey`: malformed/invalid public key encoding.
- `identity_replay`: stale identity messages with outdated timestamps.

### Contract execution probes
- `contract_gas_exhaustion`
- `contract_infinite_loop`
- `contract_large_state`
- `contract_reentrancy`

### Distributed systems chaos
- `chaos_latency`: randomized transport delay injection.
- `chaos_packet_loss`: probabilistic packet drops.
- `chaos_partition`: intermittent simulated partitions.
- `validator_isolation`: elevated partitioning pressure representing validator isolation risk.

---

## Real-IoT behavior modeling

The simulator incorporates practical IoT constraints:

- low-power pacing via per-device send interval and TPS shaping,
- intermittent connectivity via random disconnect/reconnect cycles,
- burst telemetry behavior,
- device identity rotation over transaction lifecycle.

---

## Metrics emitted (every few seconds)

- achieved TPS,
- latency: average, p95, p99,
- rejection rate,
- signature verification time (client-side probe metric),
- open/closed connection counts,
- optional node process CPU and memory usage (`--node-pid`, requires `psutil`).

---

## Run instructions

From repository root:

```bash
python3 scripts/security_toolkit_runner.py --host 127.0.0.1 --port 6001 --devices 1000 --tps 500 --duration 120 --scenarios baseline,gossip_pressure
```

Identity and parser validation sweep:

```bash
python3 scripts/security_toolkit_runner.py --devices 3000 --tps 1200 --duration 90 --scenarios replay_validation,malformed_fuzz,signature_spam,did_spoof,invalid_pubkey
```

Chaos and distributed-failure sweep:

```bash
python3 scripts/security_toolkit_runner.py --devices 2000 --tps 800 --duration 120 --scenarios chaos_latency,chaos_packet_loss,chaos_partition,validator_isolation
```

Using a scenario file:

```bash
python3 scripts/security_toolkit_runner.py --scenarios @scripts/scenario_plan.json
```

Example `scripts/scenario_plan.json`:

```json
[
  {"name": "baseline", "duration_sec": 30, "intensity": 1.0},
  {"name": "gossip_pressure", "duration_sec": 60, "intensity": 2.0},
  {"name": "replay_validation", "duration_sec": 45, "intensity": 1.5},
  {"name": "chaos_packet_loss", "duration_sec": 45, "intensity": 3.0}
]
```

---

## Design decisions and rationale

1. **Local-only default safety**: prevents accidental misuse against unauthorized targets.
2. **Asyncio per-device loop**: models high concurrency with low per-client overhead.
3. **Scenario mutators**: isolate protocol assumptions (nonce, signatures, encoding, identity binding) with repeatable probes.
4. **Chaos profile abstraction**: applies transport faults without requiring kernel-level network tooling.
5. **Metrics-first operation**: always report operational impact and rejection behavior during test windows.
6. **Composable schedule**: scenario chains enable progressive validation from baseline to high-stress conditions.

---

## Hardening recommendations for the blockchain node

1. **Message authenticity and binding**
   - Bind `senderId`, payload hash, and timestamp into signed envelope.
   - Reject if DID ↔ public key mapping does not match on-chain identity registry.

2. **Replay and nonce controls**
   - Enforce strict per-identity nonce monotonicity.
   - Add bounded timestamp skew validation and replay cache on message hash.

3. **Parser and schema hardening**
   - Enforce max message size and strict schema validation before decode.
   - Reject non-canonical encodings and unexpected types early.

4. **Crypto verification resilience**
   - Rate-limit invalid-signature sources.
   - Use staged verification (cheap structural checks before expensive signature verify).

5. **P2P resource protection**
   - Connection caps per IP / subnet / DID.
   - Idle timeout and handshake timeout.
   - Backpressure queues and bounded worker pools.

6. **Gossip control plane**
   - Dedup cache with TTL and bounded memory.
   - Peer scoring, graylisting, and decay-based ban policy.

7. **Contract execution safety**
   - Deterministic metering with hard gas/time/memory caps.
   - Reentrancy guards and per-call stack depth limits.
   - State-write quotas per transaction.

8. **Observability and forensics**
   - Expose signature-fail counters, parser-fail counters, nonce-reject counters.
   - Correlate by peer ID / DID / source IP.
   - Persist structured audit logs for anomaly triage.
