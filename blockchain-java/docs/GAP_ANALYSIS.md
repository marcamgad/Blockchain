# HybridChain — what is still missing (2026-10-22)

Snapshot after HC-085. "Verified" means there is a test or a live harness run; everything else is
read from the code. Priority: **P0** blocks a real multi-party deployment, **P1** needed before
production, **P2** quality, research or product depth.

## Where it stands

- Permissioned PBFT chain for IoT. Audit findings HC-001 to HC-085 are recorded and the fixed ones
  have tests (see `docs/audit/`); the full suite has 700+ tests and JaCoCo gates.
- Docker fault-injection harness, single host, 4 and 7 validators. **Every scenario has passed
  live:** baseline, leader crash, partition, delay/loss, restart, equivocation evidence and
  clock skew (the new ±30 s version has not had a live run yet).
- The live runs found five protocol bugs (HC-080 to HC-084) that the unit tests had not.

## 1. Consensus and protocol

| # | Gap | Pri | Notes |
|---|---|---|---|
| C1 | **Validator-set changes are not possible on a running network** | P0 | `PBFTConsensus.addValidator` is reachable only through `MultiSigManager.executeProposal`, which nothing in the node calls. Changing validators means restarting every node with new `VALIDATOR_PUBKEYS`. Wiring it as written would fork the network: proposals and signatures are node-local, expiry uses the wall clock, and `PARAMETER_CHANGE` wrote the static `Config.TARGET_BLOCK_TIME_MS`, outside the state root (removed in HC-086). Needs an on-chain `VALIDATOR_UPDATE` transaction applied at a future height (see `docs/design/PROTOCOL_UPGRADES.md`). |
| C2 | Votes, blocks and P2P handshakes are ECDSA only | P1 | Accounts can be hybrid (HC-079); consensus is not post-quantum. |
| C3 | Block interval fixed at 60 s; throughput never measured | P1 | No load test with transactions exists. The harness checks safety and liveness on empty or near-empty blocks only. |
| C4 | A validator with more than 15 s of clock skew silently stops voting | P1 | `MAX_TIMESTAMP_DRIFT` = 15 s. The network tolerates it (it's one fault) but nothing alerts the operator. Expose `/health` `time` drift and alarm on it. |
| C5 | No state snapshot / fast-sync for new nodes beyond replaying blocks | P2 | `Checkpoint` exists; there is no tested "join from snapshot" path. |
| C6 | Leader election is round-robin (HC-082); reputation-weighted election was removed for safety | P2 | Research direction 1 (commit-then-use reputation) is still unimplemented. |
| C7 | No formal model of the full protocol | P2 | TLA+ covers leader selection only (`docs/formal/`). View change, NEW_VIEW and jailing are unmodelled. |

## 2. Networking

| # | Gap | Pri | Notes |
|---|---|---|---|
| N1 | **No multi-machine run** | P0 | Every live result is one Docker host. Real latency, NAT and firewalls are untested. |
| N2 | `MAX_CONN_PER_IP = 3` | P1 | Validators behind one NAT or load balancer (more than 3 per public IP) cannot all connect. Make it configurable. |
| N3 | Gossip id = hash(sender, type, payload), with no timestamp or nonce in most message types | P2 | HC-084 added `ts` to peer lists only. Other repeat-identical messages are also deduplicated forever (bounded LRU of 1000). Review per message type. |
| N4 | Peer discovery is via a single seed plus gossip | P2 | Seed loss is now survivable (HC-084), but a node started while the seed is down has no other bootstrap address. Allow a list of seeds. |

## 3. Security and cryptography

| # | Gap | Pri | Notes |
|---|---|---|---|
| S1 | **No external security audit** | P0 | All findings come from this internal review. |
| S2 | **The REST API is plain HTTP** | P0 | Tokens and transactions cross the network unencrypted. Needs TLS (Spring `server.ssl` or a reverse proxy) and a documented setup. |
| S3 | **CoAP has no DTLS** | P1 | Telemetry is signed but not encrypted, and there is no transport authentication of devices. |
| S4 | The JWT secret is shared network-wide | P1 | Any node operator can mint a token for any address (the harness did this before HC-085). Tokens only gate API access (transactions still need signatures), but per-node or asymmetric (EdDSA/ES256) tokens would remove the shared secret. |
| S5 | Ownership-proof weaknesses (non-injective hashing, `s ≥ N` malleability, ~3 s proving) | P1 | Offered earlier, not done. |
| S6 | The CA is derived from one network seed | P1 | See `docs/design/CA_TRUST_MODEL.md`: no revocation list, no certificate expiry handling, no rotation exercised live. |
| S7 | Keys live in environment variables | P1 | `docs/design/KEY_PROVIDER.md` designs a pluggable key provider (HSM/TPM/Vault); not implemented. |
| S8 | History contains secrets (`docs/security/HISTORY_SECRET_SCAN.md`) | P1 | Those keys are denylisted (O-3), but the repository is public. Rotate anything that was ever real; a history rewrite is still your decision. |
| S9 | Login challenges are node-local, token revocation is process-local | P2 | Fine for one node per client; document it. |

## 4. API and client side

| # | Gap | Pri | Notes |
|---|---|---|---|
| A1 | No client SDK | P1 | Signing, hybrid addresses, login (HC-085) and submit-signed are only shown in Java test code. A small JS/Python SDK (or at least a CLI) is needed for anyone else to use the chain. |
| A2 | No OpenAPI spec | P2 | Endpoints are documented only in prose. |

## 5. IoT

| # | Gap | Pri | Notes |
|---|---|---|---|
| I1 | **No real device in the loop** | P0 for the IoT claim | No Pi or microcontroller has signed telemetry against a live node. `EdgeReadinessProbe` timings on a Pi are still estimates. |
| I2 | Device onboarding and attestation flow is not end-to-end | P1 | PUF challenge (HC-071) and firmware signatures (HC-074) exist as components; there is no onboarding runbook or test. |
| I3 | Sensor data on-chain (HC-058) has no oracle or aggregation design | P2 | Readings are deterministic now, but each one is a transaction; volume and cost are unmeasured. |

## 6. Smart contracts

| # | Gap | Pri | Notes |
|---|---|---|---|
| K1 | No contract developer toolchain or examples beyond tests | P2 | |
| K2 | VM/WASM fuzzing is shallow (`VmWasmFuzzTest`) | P2 | Open-roadmap item. |

## 7. Storage and operations

| # | Gap | Pri | Notes |
|---|---|---|---|
| O1 | **No CI** | P0 for a team | No `.github/workflows`: tests run only when someone runs them locally. Add CI with `mvn verify` and the `check.py` self-test. |
| O2 | No backup / restore procedure tested | P1 | LevelDB is encrypted per node; restore after disk loss is untested. |
| O3 | No pruning or retention policy | P1 | The chain grows forever; telemetry makes that worse. |
| O4 | Monitoring exists (`/metrics`), but there are no dashboards or alert rules | P2 | Alerts needed: height stall, view-change rate, clock drift, peer count, jailed validators. |
| O5 | Upgrade procedure exists on paper only (`PROTOCOL_UPGRADES.md`) | P1 | No rolling-upgrade test in the harness. |

## 8. Testing and verification

| # | Gap | Pri | Notes |
|---|---|---|---|
| T1 | Harness has no transaction load or soak run (hours/days) | P1 | |
| T2 | Equivocation is tested via evidence submission, not live double-voting on the wire | P2 | |
| T3 | No Byzantine node in the live harness (only crash, network and clock faults) | P1 | Unit-level Byzantine tests exist (`ByzantineFaultInjectionTest`). |
| T4 | Throughput/latency numbers are UNMEASURED (needs JMH, n ≥ 10) | P2 | |

## 9. Research (if this is a thesis)

| # | Gap | Notes |
|---|---|---|
| R1 | Direction 4 (collusion-resistant PUF / swarm attestation) is the strongest verified gap, but has no implementation yet | |
| R2 | M4: differential-privacy ε is not composed across federated-learning rounds | The privacy claim may be false over many rounds. |
| R3 | Direction 2 (PQ custody) depends on an unreviewed 2026 preprint | |
| R4 | No paper draft, no evaluation section with real numbers | Needs T1, T4 and I1 first. |

## 10. Product, legal and project

| # | Gap | Pri | Notes |
|---|---|---|---|
| P1 | No defined user or use case with a pilot | P1 | The explainer lists candidate uses; none is validated with a real user. |
| P2 | Personal data on an immutable ledger | P1 | Telemetry can be personal data (GDPR "right to erasure" conflicts with immutability). Keep personal data off-chain (hashes/commitments only) and write a data-protection note. |
| P3 | Dependency licences not reviewed | P2 | `LICENSE` exists for the project itself. |
| P4 | Single maintainer, no contribution guide or issue tracker in use | P2 | |

## Suggested order

1. **O1 CI** (a day): every later change gets checked automatically.
2. **S2 TLS for REST** and **N2 configurable per-IP limit**: small, and they block real deployment.
3. **N1 multi-machine run**: 3–4 cheap VMs or Pis, using the harness checks (`check.py` works against any URLs).
4. **C1 on-chain validator updates**: the largest protocol gap.
5. **A1 client SDK/CLI** and **I1 a real device**: needed for anyone to actually use it.
6. **T1 load and soak test**, then **T4 measured numbers**: needed for any performance or research claim.
7. **S1 external audit** before any production use with real value or real data.

See also [`UPGRADE_AUDIT.md`](UPGRADE_AUDIT.md) for dependency upgrades and code health.
