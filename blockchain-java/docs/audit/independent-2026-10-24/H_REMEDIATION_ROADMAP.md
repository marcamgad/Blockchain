# H — Phased Remediation Roadmap

Effort is in **days for one developer**, assuming the existing practice: failing test first, one
finding per commit, the full suite plus a live harness run before merging consensus, networking or
storage changes. Items whose scope isn't known yet carry a range and say why. This roadmap
**replaces the ordering** of `docs/UPGRADE_PLAN.md`: it moves restart safety, licensing and
multi-machine evidence ahead of platform upgrades and features.

## Phase 0 — Audit integrity (≈ 3 days)

| Task | Pri | Deps | Effort | Files | Tests | Acceptance | Risk |
|---|---|---|---|---|---|---|---|
| 0.1 Integrate `origin/main` (`SECURITY.md`), push the 10 fix commits | P1 | — | 0.1 | git | CI | CI green on `main` | merge conflict (none expected) |
| 0.2 Live harness at 4 and 7 validators on the post-fix code | P1 | 0.1 | 1 (mostly waiting) | `harness/` | all phases | all PASS; automatic jailing in the equivocation phase; clock-skew ±30 s passes | the HC-087 liveness change slows delay/loss phases → do 1.6 first |
| 0.3 Freeze claims: remove "production", "post-quantum blockchain", "DP", "ZK privacy" wording that the code doesn't support from README and docs | P1 | — | 1 | `README.md`, `docs/` | — | every claim maps to a test or to C | — |
| 0.4 Real `SECURITY.md` (C-7) and a licence decision (C-22) | P1 | — | 0.5 + your decision | `SECURITY.md`, `LICENSE` | — | contact channel live; licence chosen deliberately | business decision |

**Exit:** the baseline (B) is reproducible from `main`, and live results exist for the current code.

## Phase 1 — Consensus and state correctness (≈ 25–35 days)

| Task | Pri | Deps | Effort | Files | Tests | Acceptance | Risk |
|---|---|---|---|---|---|---|---|
| 1.1 Persist the last-signed record and the view (C-1) | **P1** | 0.2 | 3 | `PBFTConsensus`, `Storage`, `PeerNode` | restart unit test; harness kill-after-vote | no conflicting signature after restart; restarted node rejoins the current view | fsync latency on every vote; measure |
| 1.2 Per-view quorums, or a proof that mixing is safe (C-9) | P2 | 1.4 | 3 | `PBFTConsensus` | model + unit | QuorumSameView invariant | touches certificate format |
| 1.3 Randomised and validated sync peer; plausibility cap on claimed heights (C-2) | P2 | — | 2 | `PeerNode`, `PeerManager` | lying-peer test | lagging node catches up with 1 lying peer | — |
| 1.4 TLA+ model of votes, view change, NEW_VIEW, restart, jailing (D §5) | P1 | — | 10–15 (first serious model; uncertain) | `docs/formal/` | TLC | invariants pass; C-1 reproduced with persistence disabled | modelling effort underestimated |
| 1.5 Byzantine proposer and voter in the live harness | P1 | 0.2 | 4 | `harness/`, a test-only node mode | live | no honest node jailed; safety holds; Byzantine voter jailed | needs a test-only build flag; keep it out of production builds |
| 1.6 Re-broadcast the same pending proposal | P2 | 0.2 | 1 | `App`, `PeerNode` | delay/loss phases | no slower than before HC-087 | — |
| 1.7 Monotonic readings (C-3) and a lower bound on block time (C-10) | P2 | — | 3 | `DeviceReading`, `AccountState`, `Blockchain` | replay test, time test | replay can't roll back; stale timestamps refused | activation height needed |
| 1.8 Proposer-equivocation evidence and on-chain unjail (C-4, C-9) | P3 | 1.1 | 4 | `PBFTConsensus`, `Blockchain` | evidence tests | conflicting proposals jail; unjail needs quorum | — |
| 1.9 Deterministic WASM: reject float instructions or canonicalise NaN (C-5); ARM64 CI job comparing golden roots | P2 | — | 2 | `WasmContractEngine`, CI | float-module test; cross-arch golden | identical roots on amd64 and arm64 | — |
| 1.10 Stateful supply-invariant test (random sequences of mint/transfer/fee/revert/fork) | P2 | — | 2 | tests | property test | total supply = Σ balances + UTXO at every step | — |

**Exit:** D §6 items 1–3 and 5 met; restart and replay reproduce committed state; supply invariant holds under random histories.

## Phase 2 — Security hardening (≈ 15–20 days)

| Task | Pri | Deps | Effort | Files | Acceptance |
|---|---|---|---|---|---|
| 2.1 TLS for REST/WebSocket; refuse plain HTTP outside DEBUG (C-11) | P1 | — | 2 | Spring config, `SecurityConfig` | production profile won't start without TLS |
| 2.2 Per-operator CAs and certificates; drop `NETWORK_CA_SEED` (C-16) | P1 | — | 4 | `CertificateAuthority`, `App`, `gen.sh` | each operator issues only its own nodes' certificates |
| 2.3 Asymmetric API tokens (ES256), no shared `JWT_SECRET` (C-16) | P1 | — | 2 | `JwtManager`, login | tokens verifiable per issuing node |
| 2.4 Key provider: PKCS#11 / encrypted file; no keys in environment variables | P1 | — | 4 | `KEY_PROVIDER.md` design | validator key never in env or logs |
| 2.5 WebSocket limits and reaping (C-6); rate limit before authentication | P2 | — | 1.5 | WebSocket, filters | N+1 session refused |
| 2.6 Replace or remove the custom ownership proof (C-18) | P2 | — | 2–5 | `ZKProofSystem` | reviewed library, or the feature removed |
| 2.7 DP: fail closed and add accounting, or remove the claim (C-8) | P3 | — | 1–3 | `DPMechanism` | — |
| 2.8 Spring Boot 4.1, Java 21 (C-21) | P2 | 1.x | 5 | pom, CI, Docker | golden tests unchanged; harness green |

**Exit:** a review-ready security report; all P1 items in C closed.

## Phase 3 — Distributed reliability (≈ 15 days)

| Task | Pri | Effort | Acceptance |
|---|---|---|---|
| 3.1 Multi-machine harness (4–7 VMs or Pis, different networks) | P1 | 4 | all scenarios PASS on real machines |
| 3.2 Process-kill crash injection during block persistence (100+ iterations) | P1 | 2 | 0 inconsistent stores; 0 divergence |
| 3.3 24-hour soak under load with periodic faults | P1 | 3 | no leaks, no stall, bounded disk growth |
| 3.4 Upgrade across an activation height, rolling | P2 | 3 | chain continues through the upgrade |
| 3.5 Backup and restore of a node from a checkpoint | P2 | 3 | restored node matches roots |

**Exit:** repeatable results for the whole failure matrix (I §3).

## Phase 4 — Performance engineering (≈ 20–30 days; depends on the measurements)

| Task | Pri | Effort | Acceptance |
|---|---|---|---|
| 4.1 Load generator and metrics (F §4) | P1 | 4 | reproducible latency/throughput curves |
| 4.2 Compact binary wire format for transactions (hybrid transactions are 7.6 KB as JSON) | P1 | 3 | ≥ 2× more transactions per block |
| 4.3 Incremental trie storage (nodes by hash) instead of a full-state JSON write per block | P1 | 8–12 | per-block cost independent of state size |
| 4.4 Shorter block time via activation height; view timeout derived from it | P2 | 2 | finality in seconds |
| 4.5 Per-peer send queues; virtual threads | P2 | 2 | no head-of-line blocking |
| 4.6 Parallel signature verification | P2 | 2 | validation time ÷ cores |

**Exit:** measured, reproducible numbers with stated scaling limits; the ceiling of C-19 removed.

## Phase 5 — Focused application (≈ 25–35 days)

The G §3 cold-chain evidence ledger: SDK for the gateway, device provisioning tooling, the
breach contract, the dispute report, and the comparison with Fabric and the database + Tessera
baseline (F §4).

**Exit:** a 30-day multi-operator pilot plus a comparison report showing a measurable advantage, or
a documented decision to move the device-trust layer onto an established engine.

## Phase 6 — Independent validation (external; not solo work)

External review of consensus, networking, cryptography and storage; a reproducibility review of
the benchmarks; a deployment and recovery review; a threat-model review.

**Exit:** the remaining risks are documented, and accepted or remediated by the pilot's stakeholders.

## Totals

About **105–145 developer-days** for phases 0–5 (5–7 months for one person), plus the external
review. The ranges are wide where the work hasn't been scoped yet (1.4 formal model, 4.3 storage,
Phase 5 integration).

## Rule

No new features until Phase 1 is complete. A feature built on a consensus layer that can jail
honest validators after a restart inherits that flaw.
