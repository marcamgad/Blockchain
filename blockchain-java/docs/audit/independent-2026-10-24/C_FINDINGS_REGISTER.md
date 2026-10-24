# C — Security and Correctness Register

Revision `12fb385`. Paths are relative to `blockchain-java/src/main/java/com/hybrid/blockchain/`
unless stated otherwise.

## Severity rubric

| Level | Meaning |
|---|---|
| **P0 / Critical** | Demonstrated or strongly substantiated conflicting finality, unauthorised control of critical state, or similar systemic failure |
| **P1 / High** | Blocks a credible production or pilot deployment |
| **P2 / Medium** | Material risk with constrained impact or a compensating control |
| **P3 / Low** | Limited impact or defence in depth |
| **Info** | No direct security impact established; relevant to architecture or future risk |

## Part 1 — Status of previously reported findings

"Fixed" below means: the code changed, a test reproduces the old failure and now passes, and the
full suite passes (724/724 on a clean checkout, B §3). None of these fixes has yet run in the
live multi-process harness. That is a separate, still-open validation step (H, task 0.2).

| ID | Previous claim | Status now | Evidence | Residual |
|---|---|---|---|---|
| HC-087 | Proposals not checked against the leader; multiple PREPAREs per height; self-evidence | **Fixed and regression-tested** (component level) | `consensus/PBFTConsensus.java:649` `reserveOwnVote`; `PeerNode.onProposal` leader check; `ProposalVotingTest` failed before the fix *[test]* | **HC-095**: vote memory is lost on restart *[probe]* |
| HC-088 | Automatic evidence rejected under `REQUIRE_QUANTUM_SIG` | **Fixed and regression-tested** | `consensus/EvidenceTransactions`; `EvidenceTransactionTest` | The node's ML-DSA key is regenerated per process; harmless for `hb` accounts (HC-079) |
| HC-089 | Tip and state written non-atomically | **Fixed and regression-tested** (simulated failure) | `Storage.beginBatch/commitBatch`; `BlockPersistenceAtomicityTest` failed before the fix | No process-kill crash injection yet; checkpoint and telemetry writes outside block application are separate (by design) |
| HC-090 | State root depends on Jackson text | **Fixed and regression-tested** | `CanonicalEncoding`; `StateRootV2Test` (golden value, round trip) | The identity tree still goes through Jackson `convertValue` (structure, not text); guarded only by the golden test; not checked across OSes/CPUs |
| HC-091 | Block signature without domain or proposer id | **Fixed and regression-tested** | `Block.signingDigest`; `BlockSignatureDomainTest` | — |
| HC-092 | Dormant COMMIT-without-PREPARE path | **Fixed** (removed) | `NoFastPathTest` (structural) | — |
| HC-093 | Revoked DID re-mapped on restart | **Fixed and regression-tested** | `DidRevocationPersistenceTest` failed before the fix | — |
| REST API over plain HTTP | | **Confirmed by code inspection** | No `server.ssl`, no `application.properties`; `SecurityConfig` has no channel security | see C-11 |
| No multi-machine validation | | **Confirmed** (absence) | Harness is single-host Docker only (`harness/fault-injection/README.md`) | see C-12 |
| No load, soak or crash-injection testing | | **Partially addressed** | Crash = simulated exception (HC-089 test); no load or soak harness exists | see C-13 |
| Static validator set; consensus/networking not post-quantum | | **Confirmed by code inspection** | `MultiSigManager.executeProposal` never called; votes and handshake ECDSA-only | see C-14, C-17 |
| No external audit | | **Confirmed** (absence) | No third-party report in the repository | see C-15 |

## Part 2 — New and open findings

### C-1 · HC-095 · Validator vote state and view are not persisted (restart amnesia)

**Severity:** P1 · **Confidence:** Confirmed (reproduced by probe)

> **Status 2026-10-24:** **Fixed and regression-tested at component level** (HC-095 commit). A durable
> signing record (`consensus-signing-record.json` in `STORAGE_PATH`) is written with fsync and an
> atomic rename **before** every vote, and loaded at start-up; the view is restored too. A failed
> write means no signature; a corrupt record stops the node. Test: `SigningRecordTest` (failed on the
> old in-memory behaviour). **Still open for acceptance:** the live harness kill-after-vote scenario
> with a Byzantine proposer (H 1.1 / 1.5).

**Affected:** `consensus/PBFTConsensus.java:639` (`ownVotes`, in memory), `:207`
(`viewNumber = 0` in the constructor); `PeerNode.sentCommits` (in memory); no reference to `Storage`
anywhere in `PBFTConsensus`.

**Evidence:** *[probe]* `AuditProbes.restartAmnesia`. Before the restart: a conflicting PREPARE was
refused. After constructing a new instance (a process restart): `conflictingVoteAllowedAfterRestart=true`,
`viewAfterRestart=0`.

**Preconditions:** A validator restarts (crash, upgrade, OOM) during a view in which it has already
voted, and a Byzantine leader of that view (within *f*) sends it a second, validly signed proposal
for the same height.

**Sequence:**
1. Validator V signs PREPARE(v, h, A).
2. V restarts, and its `ownVotes` are empty.
3. The Byzantine leader sends B for (v, h).
4. V passes the leader check (if it is in view v) and the empty reservation, and signs PREPARE(v, h, B).
5. The leader submits `EQUIVOCATION_EVIDENCE` with both signatures.
6. V is jailed permanently (there is no unjail, see C-9).

The view resetting to 0 also means V can't vote with peers in a later view until it receives a
NEW_VIEW (liveness).

**Impact:** A Byzantine validator can shrink the honest set by one for every restart it catches. With
n = 4, one success halts the chain: 2 jailed out of 4 leaves too few validators for quorum.

**Existing controls:** The leader check makes the window depend on the attacker leading the
restarted node's view. That narrows it, but doesn't close it.

**Remediation:** Persist the last-signed record before sending: (phase, view, seq, hash), plus the
current view. Write it synchronously to storage before the signature leaves the process, as
CometBFT's private-validator state does. On start-up, load it, and refuse to sign anything that
conflicts with it.

**Regression test:** Sign PREPARE(v, h, A), close and reopen storage and consensus, and attempt
PREPARE(v, h, B). It must be refused, and no evidence may be producible.

**Acceptance:** That test, plus a harness scenario that kills a validator right after it votes and
restarts it while a Byzantine proposer runs. No honest validator may be jailed.

**Dependencies:** none. **Residual:** a disk loss loses the record. Document that a validator restored
from backup must not sign in the views covered by the lost record (operational rule).

### C-2 · HC-096 · Catch-up trusts a single peer's self-reported height

> **Status 2026-10-24: Fixed and regression-tested** (`50d6c89`, `p2p/SyncPeerSelector`, `SyncPeerSelectorTest`).


**Severity:** P2 · **Confidence:** Strong evidence (code inspection)

**Affected:** `PeerNode.java:110` `recordPeerHeight` (a peer's height only ever increases, from
the peer's own `PEER_DISCOVERY` entry), `:533` `syncBlocksWithPeers` (always the single highest peer).

**Sequence:** A Byzantine validator advertises a height of 2⁶²; every lagging node picks it as
`bestPeer` and requests blocks; it returns none or junk (−5 score for each bad block). The node's
recorded height for it never falls, so the node keeps choosing it.

**Impact:** Restarted or partitioned honest nodes can't catch up while that peer stays connected.
That's a liveness failure for those nodes, inside the fault model.

**Remediation:** Choose sync peers at random among peers claiming more than the local height; drop
a peer's claimed height when it fails to deliver; cap claims at a plausible rate (height of the
local tip plus elapsed time / block time).

**Regression test:** Simulate one peer claiming a huge height and returning nothing; the node must
reach the honest peers' height. **Residual:** none significant.

### C-3 · HC-097 · Replayed device readings roll back the contract-visible sensor value

> **Status 2026-10-24: Fixed and regression-tested** (`585b70b`, reading times in state root v2, `ReadingReplayTest`). A per-device counter in `DeviceReading` is not added (not needed for the rollback).


**Severity:** P2 · **Confidence:** Strong evidence (code inspection)

**Affected:** `lifecycle/DeviceReading.java:32,96` (acceptance window ±10 min, no nonce or
sequence); `AccountState.java:475` `recordReading` (unconditional overwrite);
`Blockchain.java` around line 1498 (records any device-authenticated reading).

**Sequence:** A signed reading R1 (value 30) is published in transaction data; R2 (value 80)
follows; within 10 minutes of R1's timestamp, anyone who may relay telemetry resubmits R1. The
latest-reading slot becomes 30 again, and contracts see 30.

**Impact:** Short-window manipulation of the on-chain oracle value. That matters for any contract
that triggers on thresholds (the project's stated IoT use).

**Remediation:** Store the reading's timestamp next to the value; accept only strictly newer
readings per device; add a per-device monotonic counter to `DeviceReading` (behind an activation
height).

**Regression test:** Submit R1, R2, then R1 again; the value stays R2's. **Residual:** a compromised
device key can still sign false values. That's origin, not truth (see D §8).

### C-4 · HC-094 · Proposer equivocation is not punishable

**Severity:** P3 · **Confidence:** Strong evidence (code inspection)

**Comparator note:** CometBFT's signing specification states that it does not currently handle
evidence of conflicting proposals either, so this is parity with CometBFT, not a gap against it.
Here the cost is larger, because a stalled view lasts 135 s or more, against CometBFT rounds of
seconds.

**Affected:** `consensus/PBFTConsensus.java:1082–1103`: evidence covers PREPARE and COMMIT votes
only. A proposal's signature (`Block.signingDigest`) is never compared across two blocks.

**Sequence:** In each of its views, a Byzantine leader sends different blocks to different
validators. HC-087 now stops honest validators from double-voting, so no quorum forms. The view
times out (135 s, then backing off up to 1,080 s). Nothing is jailed. Round-robin gives it 1/n of
all views, indefinitely.

**Impact:** A sustained liveness reduction from one validator, without consequences.

**Remediation:** Make two proposer signatures for the same (view, height) with different block
hashes valid `EQUIVOCATION_EVIDENCE` (a new phase `PROPOSAL`, activation height). Honest
validators forward both blocks when they see a conflict.

**Regression test:** Two signed proposals for one (view, height) produce verifiable evidence that
jails the proposer. **Dependencies:** C-1 (so evidence can't be manufactured against restarted
honest nodes).

### C-5 · HC-098 · Floating-point WebAssembly is allowed in consensus execution

> **Status 2026-10-24: Fixed and regression-tested** (`744974f`, `WasmDeterminism`, `WasmDeterminismTest`). An ARM64 CI comparison of golden roots is still to do.


**Severity:** P2 · **Confidence:** Suspected (not reproduced; needs a non-x86 validator)

**Affected:** `WasmContractEngine.java` (Chicory 1.0.0; no rejection of f32/f64 instructions);
`ai/SmartContractAuditor.java` (no float check).

**Reasoning:** *[inference, documented WASM behaviour]* The WebAssembly specification lets
floating-point operations return NaNs with non-deterministic payloads. Chicory executes on JVM
floating point, and the NaN bit patterns hardware produces differ between x86 and ARM. A contract
that reinterprets a NaN result as an integer and stores it can therefore produce different state
on x86 and ARM validators. Production WASM chains reject float instructions or canonicalise NaNs
for exactly this reason.

**Remediation:** Reject modules containing float instructions at deploy time, or canonicalise NaN
results in the interpreter.

**Regression test:** Deploying a module with `f32.div` is refused (or, with canonicalisation,
`0/0` stored as bits gives one fixed value). Run the golden scenario on ARM64 CI.

### C-6 · HC-099 · WebSocket sessions are unbounded and never reaped

**Severity:** P2 · **Confidence:** Strong evidence (code inspection)

**Affected:** `api/WebSocketConfig.java:21` (`setAllowedOrigins("*")`);
`api/EventBusWebSocketHandler.java:26–34` (heartbeat is an empty placeholder; no session limit).
The handshake requires a JWT, but since HC-085 anyone can get one by generating a key pair.

**Impact:** Memory and file-descriptor exhaustion of a validator's API process by one client.

**Remediation:** A per-IP and per-subject session cap; real ping/pong with idle close; an origin
allow-list.

**Test:** Opening N + 1 sessions from one subject is refused.

### C-7 · HC-100 · `SECURITY.md` is the unedited template

**Severity:** P3 · **Confidence:** Confirmed

**Evidence:** `origin/main:SECURITY.md` lists versions 5.1.x, 5.0.x and 4.0.x (which don't exist)
and has no reporting channel.

**Impact:** Researchers have no way to report vulnerabilities, and the version table is misleading.

**Remediation:** A real policy: a private contact (GitHub private vulnerability reporting), the
supported branch (`main` only), and expected response times.

### C-8 · HC-101 · Differential privacy fails open and has no accounting

**Severity:** P3 (advisory component) · **Confidence:** Strong evidence

**Affected:** `ai/DPMechanism.java:26,44`: `epsilon <= 0` returns the raw values. There's no
privacy budget or composition across federated-learning rounds.

**Impact:** No privacy guarantee can be claimed for repeated aggregation. A misconfiguration
silently disables noise.

**Remediation:** Reject ε ≤ 0; add an accountant (for example Rényi DP) per participant across
rounds; document the guarantee or remove the claim.

### C-9 · HC-102 · Votes from different views combine in one quorum count; jailing is permanent

> **Status 2026-10-24: same-view quorums fixed and regression-tested** (`b5e984c`, `SameViewQuorumTest`). The fix also closed a sharper variant: a prepared proof could claim a later view with one Byzantine signature. **Still open:** on-chain unjail.


**Severity:** Info / P3 · **Confidence:** Suspected (no counterexample found)

**Affected:** `consensus/PBFTConsensus.java:158`: the vote log is `seq → phase → validator`, with no
view key, so `getQuorumHash` counts the latest vote of each validator regardless of view. And no
code removes a validator from the jail list.

**Reasoning:** Classical PBFT safety proofs count quorums within one view. Mixing views wasn't
exploitable in the reasoning done for this audit, because HC-087's rules plus NEW_VIEW
justification limit what can be voted. It is outside the standard proof, though, and must be
covered by the formal model (D §6). Permanent jailing turns any false-positive evidence
(see C-1) into an unrecoverable loss of a validator.

**Remediation:** Key the vote log by view; require quorum votes from one view. Add an on-chain
unjail transaction that needs a quorum of the other validators.

### C-10 · HC-103 · Block timestamps have no lower bound relative to real time

> **Status 2026-10-24: Fixed and regression-tested** (`e13fd14`, `ProposalVotingTest` case e). A validator more than 15 s off in either direction now stops voting until its clock is fixed.


**Severity:** P3 · **Confidence:** Strong evidence

**Affected:** `Blockchain.java:872` (only `> parent` and `≤ now + 15 s`).

**Impact:** A Byzantine leader can let block time drift behind real time. Block time anchors the
±10 min reading window (C-3) and certificate and credential expiry checks.

**Remediation:** Honest validators refuse proposals whose timestamp is more than X behind their
clock (a BFT-time rule, as CometBFT has).

### C-11 · Plain-HTTP REST and WebSocket API

**Severity:** P1 for any pilot · **Confidence:** Confirmed by code inspection.

Tokens, signed transactions and telemetry cross the network unencrypted. **Remediation:** TLS
termination (Spring `server.ssl` or a reverse proxy), HSTS, and refusing plain HTTP outside DEBUG.

### C-12 · No multi-machine operation evidence

**Severity:** P1 for any pilot · **Confidence:** Confirmed (absence).

### C-13 · No load, soak or process-kill crash testing

**Severity:** P1 for any pilot · **Confidence:** Confirmed (absence).

### C-14 · Static validator set; governance code is unwired

**Severity:** P1 for any multi-party deployment · **Confidence:** Confirmed by code inspection
(`MultiSigManager.executeProposal` has no caller in main code). Changing validators means stopping
every node.

### C-15 · No independent security review

**Severity:** P1 for production · **Confidence:** Confirmed (absence).

### C-16 · Shared network-wide secrets and keys in environment variables

**Severity:** P1 for a multi-operator pilot · **Confidence:** Confirmed by code inspection.
`NETWORK_CA_SEED` lets every operator issue TLS certificates for every node. `JWT_SECRET` lets every
operator mint API tokens for any address. `NODE_PRIVATE_KEY` sits in the environment.

### C-17 · Post-quantum protection covers `hq` accounts only

**Severity:** P2 (positioning) · **Confidence:** Confirmed by code inspection.
Votes, blocks, certificates, handshakes and validator identities are ECDSA/P-256 only. "Post-quantum"
must not be claimed for the system.

### C-18 · Custom Schnorr ownership proof, unaudited

**Severity:** P2 · **Confidence:** Strong evidence (previous review, not re-derived here).
Earlier review notes: non-injective transcript hashing, no `s < N` check, BigInteger scalar
arithmetic (about 3 s per proof, not constant-time). **Remediation:** replace with a reviewed
library implementation (for example a standard Schnorr/BIP-340-style proof) or remove.

### C-19 · Protocol throughput ceiling of about 4.6 tx/s in production configuration

**Severity:** P1 for any IoT pilot at scale · **Confidence:** Confirmed by measurement and
arithmetic (F §2).

### C-20 · Production entry point untested by the unit suite

**Severity:** P2 · **Confidence:** Confirmed (JaCoCo). `com.hybrid.App` has 0% line coverage: the
proposer loop, evidence listener and wiring, which is where HC-087's natural trigger lived.
`PeerNode` has 38.5% branch coverage. These paths are exercised only by the live harness.

### C-21 · Platform out of support

**Severity:** P2 · **Confidence:** Confirmed *[doc]*. Spring Boot 3.5 has had no free updates since
June 2026; Oracle premier support for Java 17 ended September 2026 (`docs/UPGRADE_AUDIT.md` U1/U2,
with sources).

### C-22 · The licence forbids commercial use and public modification

**Severity:** Info (no security impact), but **decisive for adoption** · **Confidence:** Confirmed

**Evidence:** `LICENSE` ("Non-Commercial Source-Available License (2026)"): use is permitted only for
personal, educational, research or other non-commercial purposes. Restrictions (a) and (e) forbid
making modified versions publicly available and any commercial, for-profit or business use.

**Impact:**
- No company can run a pilot, internal or otherwise, without a separate licence.
- External contributors can't publish forks or patches.
- The licence is not OSI open source, so foundations, distributions and most enterprise procurement processes exclude it outright.
- The repository itself is public on GitHub, which invites use the licence then forbids.

**Remediation:** a deliberate decision.
- For adoption and contributions: an OSI licence such as Apache-2.0, the licence of Fabric, Besu and CometBFT.
- To keep commercial control: keep this licence, but state a commercial-licence contact, and accept that it rules out an open community.

## Root causes shared across findings

1. **Consensus state treated as volatile** (C-1, C-9): nothing in consensus survives a restart.
2. **Trust in self-reported or unordered data** (C-2, C-3, C-10): heights, readings and timestamps are accepted without monotonicity or plausibility rules.
3. **Validation inside one process, never across machines** (C-12, C-13, C-20): the tests are strong at component level and weak exactly where distributed failures happen.
4. **Shared trust roots** (C-16): one seed and one secret for everyone.
