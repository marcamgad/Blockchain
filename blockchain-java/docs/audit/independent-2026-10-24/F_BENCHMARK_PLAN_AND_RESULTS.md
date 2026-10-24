# F — Benchmark Plan and Results

**Everything under "Measured" was measured for this report.** It is a single run on one developer
laptop, so treat it as an order-of-magnitude baseline, not a benchmark. Everything else is plan.

## 1. Environment of the measurements

| | |
|---|---|
| Machine | Windows 11, amd64, 8 logical CPUs |
| JVM | OpenJDK 17.0.18, `-Xmx2g`, default GC |
| Code | clean worktree at `12fb385` |
| Probe | `AuditProbes` (scratchpad, not in the repository), production mode `REQUIRE_QUANTUM_SIG=true` |
| Method | warm-up of 100 operations, then n = 300 timed single-thread iterations (crypto); single runs otherwise |

## 2. Measured

### 2.1 Cryptography (ms per operation, single thread)

| ECDSA sign | ECDSA verify | ML-DSA-65 sign | ML-DSA-65 verify |
|---|---|---|---|
| 0.299 | 0.381 | 0.592 | 0.315 |

ML-DSA signature size: 3,309 bytes; public key encoding: 1,974 bytes.

### 2.2 Transaction size and the protocol ceiling

| Transaction | JSON size on the wire |
|---|---|
| ECDSA only | 590 bytes |
| Hybrid (ECDSA + ML-DSA, required by default) | **7,630 bytes** |

`validateBlock` limits the serialised size of a block's transactions to 2 MB, and blocks come every
60 s. So **at most 274 hybrid transactions fit in a block, and the protocol ceiling is 274 / 60 s ≈
4.6 tx/s.** With `REQUIRE_QUANTUM_SIG=false`, the count limit (2,000 per block) applies instead: ≈ 33 tx/s.
These are upper bounds set by configuration, not throughput measurements.

### 2.3 State size (accounts only, no devices)

| Accounts | State root (fresh) | State root after **one** change | Full-state JSON | JSON size |
|---|---|---|---|---|
| 1,000 | 114 ms* | 7.8 ms | 31 ms | 132 KB |
| 10,000 | 45 ms | 51.7 ms | 68 ms | 1.3 MB |
| 50,000 | 106 ms | 110.7 ms | 297 ms | 6.7 MB |

\* the first run includes JIT warm-up.

**Reading:** the root after a single change costs as much as a fresh computation, so the trie
root isn't incremental. Every block also writes the full state as one JSON document. Both grow
linearly with the state: at 50,000 accounts that's about 0.4 s and 6.7 MB of writes per block,
however small the block.

### 2.4 Block application (validation, execution, root, atomic persistence)

| Transactions in block | Apply time | Per transaction |
|---|---|---|
| 1 | 122 ms | — (dominated by fixed cost and warm-up) |
| 10 | 60 ms | 6.0 ms |
| 40 | 124 ms | 3.1 ms |

Small state, hybrid-signed transfers, single-node apply (no networking or voting).

## 3. What is not measured (and must not be claimed)

End-to-end throughput, confirmation latency percentiles, finality time, bandwidth, CPU and memory
under load, sync time, recovery time, behaviour at 7+ validators, behaviour on separate machines.

## 4. Benchmark programme

### 4.1 The question

> For multi-operator device-attested records, what does HybridChain provide that a Fabric network
> with equivalent chaincode, or a database plus a transparency log, does not, and at what cost?

### 4.2 Systems under test

1. **HybridChain** (after the P1 items of C), 4 and 7 validators on separate machines.
2. **Fabric 3.x with SmartBFT**, with chaincode implementing the same device lifecycle and reading rules. This is the closest permissioned BFT comparator.
3. **Baseline:** PostgreSQL + device-signed events + Tessera log (one operator, with public inclusion proofs).
4. *(Optional, for consensus research)* the same application logic on **CometBFT** via ABCI.

### 4.3 Workload (identical across systems)

| Operation | Share |
|---|---|
| Device-signed reading (`TELEMETRY` with `DeviceReading`) | 90 % |
| Lifecycle event (provision, activate, suspend, revoke, firmware) | 5 % |
| Ownership transfer | 1 % |
| Query: latest authenticated reading; device status at height *h* | 4 % (read path, measured separately) |

Device population: 1,000 / 10,000 / 100,000 devices. Arrival: Poisson at a set offered load, stepped
until saturation.

### 4.4 Metrics

- offered vs accepted vs finalised transactions per second;
- p50 / p95 / p99 submit-to-final latency;
- CPU, RSS and disk writes per node;
- bandwidth per node;
- messages per block;
- state-root time;
- restart-to-caught-up time;
- time to detect and jail an equivocating validator.

### 4.5 Fault conditions

Normal; 1 crashed validator; 10% loss; 300 ms ± 100 ms delay; partition and heal; a Byzantine
proposer; a Byzantine voter; a restart during voting (C-1); clock skew of ±30 s.

### 4.6 Procedure

- One warm-up (10 min, discarded), then three runs of 30 min per point.
- Every run starts from a fresh deployment produced by one script.
- Correctness checks after every run with `harness/fault-injection/check.py`: same block hash and state root per height on all nodes, plus supply conservation.
- Raw results (CSV per second, node metrics) stored under `docs/benchmarks/<date>/`, with configuration, versions and hardware listed.

### 4.7 Threats to validity

Different implementation languages and transaction encodings; Fabric endorsement policies; disk
and cloud variance; and the fact that the author of the system under test also writes the
comparator's chaincode. Mitigate by publishing all code, and by having someone else review the
Fabric chaincode.

### 4.8 What counts as an advantage

HybridChain may claim an advantage only if it is shown, with the above, to be at least one of:
- equally correct under all fault conditions **and** lower integration effort (lines of
  application code, defects found during implementation, time to onboard a device);
- or measurably better on a metric that matters for the use case, at comparable correctness.

**If neither holds, the honest conclusion is to build the device-trust layer on an established
engine** (Fabric chaincode, or a CometBFT application).
