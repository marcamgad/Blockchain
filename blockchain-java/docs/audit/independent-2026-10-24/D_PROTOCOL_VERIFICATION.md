# D — Protocol Verification Report

## 1. What is actually implemented

A **PBFT-family protocol with custom simplifications**. It is not the original PBFT (Castro and
Liskov, 1999) and not Tendermint/CometBFT, although it borrows from both. Source:
`consensus/PBFTConsensus.java`, `PeerNode.java` (messaging), `com/hybrid/App.java` (proposer).

| Element | Implementation | Classical PBFT | Note |
|---|---|---|---|
| Membership | Fixed `VALIDATOR_PUBKEYS` at start; `add/removeValidator` exist but nothing calls them on-chain | fixed or reconfigured | static in practice (C-14) |
| Fault model | n ≥ 4, f = ⌊(n−1)/3⌋ Byzantine | same | |
| Quorum | q = ⌈(n+f+1)/2⌉ | 2f+1 when n = 3f+1 | correct for every n (HC-002) |
| Leader | `eligible[v mod |eligible|]`, sorted, jailed removed | primary = v mod n | deterministic, no reputation |
| Proposal | the leader's signed block; accepted only from `selectLeader(localView)` (HC-087) | PRE-PREPARE with view | the proposal carries no explicit view |
| Votes | PREPARE, then COMMIT; each validator signs at most one hash per (phase, view, seq), **in memory only** | same, with a persistent log | C-1 |
| Vote counting | latest vote per validator per (seq, phase), **without view** | per view | C-9 |
| Commit | q COMMITs for one hash → apply; commit certificate stored in the block | same | |
| View change | timeout 135 s × 2^min(k, 3); VIEW_CHANGE carries a prepared proof; NEW_VIEW re-proposes the highest prepared block, re-derived by every node | same idea | |
| Finality | immediate when committed; certificates let syncing nodes verify | same | |
| Ordering | canonical execution order MINT → sender → nonce (independent of the order in the block) | n/a | |
| Evidence | double PREPARE/COMMIT → on-chain jail; no unjail; proposals not covered | n/a (accountability added) | C-4, C-9 |
| Synchrony | partial synchrony assumed for liveness; safety asynchronous | same | |
| Restart | vote memory and view lost; chain state recovered atomically (HC-089) | log on stable storage | C-1 |
| Catch-up | sync from the single highest peer, certificate-verified | state transfer | C-2 |
| No quorum | the chain stops; validators keep timing out with back-off up to 1,080 s | same | expected |

**Conclusion:** the protocol's *structure* matches PBFT's normal case and view change. Its
*persistence and accountability layers* are custom and incomplete. Calling it "PBFT" is fair for the
message flow; calling it **PBFT-correct** is not yet justified.

## 2. Safety and liveness properties

Assumptions: at most f Byzantine validators; authenticated channels; correct hash and signature
functions; the honest validators' storage survives restarts unless stated.

| # | Property | Status | Evidence |
|---|---|---|---|
| S1 | No two honest validators finalise different blocks at one height | **Holds in tests; unproven** | `ByzantineFaultInjectionTest` (0/60 violations), live harness before HC-087; no proof |
| S2 | An honest validator never signs conflicting votes for one (phase, view, seq) | **Holds within one process; violated across restarts** | `ProposalVotingTest`; probe C-1 |
| S3 | Only the view's leader may propose | **Holds** (for fresh proposals; NEW_VIEW re-proposals are justified instead) | `onProposal`, `acceptNewView` |
| S4 | A proposal cannot bypass prepared-proof rules | **Holds by code inspection** | `acceptNewView` re-derives `choosePrepared`; `NewViewTest` |
| S5 | No invalid commit certificate is accepted | **Holds by inspection and test** | `verifyCommitCertificate`; `CommitCertificateTest` |
| S6 | Duplicate identities don't inflate quorums | **Holds** | votes keyed by validator id; certificate uses a set |
| S7 | Votes for different hashes don't combine | **Holds** | `getQuorumHash` per hash (S7-01) |
| S8 | Old-view messages can't wrongly influence a new view | **Unverified** | votes counted without view (C-9) |
| S9 | A restart doesn't violate earlier safety | **Fails** | C-1 (reproduced) |
| S10 | Identical committed history → identical state | **Holds in tests on one platform** | golden root, round-trip tests; not cross-platform (C-5) |
| S11 | Malformed messages don't change consensus state | **Mostly holds** | bounded frames, signature checks first (HC-044); not fuzzed |
| S12 | Jailing/membership never makes honest nodes disagree on eligibility | **Holds for jailing** (on-chain only, O-5); membership changes don't exist | |
| L1 | With ≥ q honest validators online and a synchronous period, blocks commit | **Holds in harness** | partition/healed, leader-crash phases (pre-HC-087 run) |
| L2 | A lagging honest node catches up | **Can fail** | C-2 |

## 3. Adversarial scenario coverage

| Scenario | Covered by | Status |
|---|---|---|
| Byzantine leader, conflicting blocks | `ProposalVotingTest` (receiver side) | component only; **not in live harness** |
| Proposal from the wrong leader | `ProposalVotingTest` | component only |
| Honest node receives conflicting proposals | `ProposalVotingTest` | covered |
| Duplicate, delayed, reordered and replayed votes | `ByzantineFaultInjectionTest` (randomised), gossip dedup | partial; replay across views not tested |
| Partitions and asymmetric loss | live harness `partition`, `delay-loss` | single host, pre-fix |
| Simultaneous view changes | not targeted | **gap** |
| Crash before/after PREPARE/COMMIT | none | **gap** (C-1) |
| Restart with partial state | `BlockPersistenceAtomicityTest` (simulated) | storage only |
| Conflicting prepared proofs | `NewViewTest` | covered |
| Invalid/incomplete certificates | `CommitCertificateTest`, audit PoCs | covered |
| Inconsistent messages to different peers | `ByzantineFaultInjectionTest` (equivocating voter) | covered for voters, not proposers |
| Clock skew | live harness ±30 s (written, not yet run live) | pending |
| Untrusted catch-up peer | certificate checks | covered for content, **not for withholding** (C-2) |
| Stale snapshot | `CheckpointFastSyncPentest`, `CheckpointRecoveryTest` | covered |
| Evidence with bad/missing signatures | `verifyEvidence` tests, audit PoCs | covered |
| Quorum loss and recovery | live harness `leader-crash` | pre-fix |
| n = 4, 5, 7, 10 | quorum unit tests; live 4 and 7 | 5 and 10 not run live |

## 4. Formal verification status

- **Exists:** `docs/formal/LeaderSelect.tla` with TLC configurations. It models the **old** reputation-weighted leader selection only, and found the agreement counterexample that led to the round-robin rule.
- **Not modelled:** votes and quorums, view change and NEW_VIEW, certificates, jailing, restarts, persistence, Byzantine message sending.
- **Simplifications in the existing model:** two correct nodes, no Byzantine processes, bounded steps, integer reputation, correspondence to Java asserted by reading.
- **Conclusion:** no safety property of the current consensus protocol is formally verified.

## 5. Model to build (H task 1.3)

TLA+, with processes for n = 4 (1 Byzantine) and n = 7 (2 Byzantine), and actions Propose, Prepare,
Commit, Timeout, ViewChange, NewView, Crash, Restart(with or without the persisted last-signed
record) and a Byzantine "send anything signed by itself".

Invariants:
- Agreement (S1);
- NoHonestEquivocation (S2), which **must fail** when Restart drops the record;
- CommittedNeverReplaced;
- QuorumSameView (S8), checked both ways to settle C-9.

The model is useful only if it reproduces C-1 when the record is not persisted; that's the acceptance test for the model itself.

## 6. Exit criteria for "consensus production-ready" (not met)

1. C-1 (persisted signing record) fixed, and a kill/restart scenario in the harness passes.
2. C-9 decided: per-view quorums, or a model-checked argument for mixing.
3. A Byzantine proposer and a Byzantine voter in the **live** harness, at 4 and 7 validators.
4. The live harness at 4 and 7 validators on **separate machines**.
5. The TLA+ model above, with its invariants passing and its intended counterexample reproduced.
6. An independent review of `PBFTConsensus`, `PeerNode` messaging and `App` proposer code.

## 7. Liveness note on HC-087

HC-087 removed the 60-second re-proposal. A proposal lost in transit is now replaced only by a
view change (135 s, then back-off). The delay/loss harness phases have not run since; if their
times grow, re-broadcasting the **same** proposal is the safe remedy (`docs/UPGRADE_PLAN.md`,
task 1.4).

## 8. Origin is not truth

A valid device signature proves that the holder of the device key signed the reading, under the
key-management assumptions. It does not prove the reading is physically accurate, or that the
sensor or its firmware was not compromised. The ledger can prove origin, order and lifecycle state
at the time of the reading, nothing more.
