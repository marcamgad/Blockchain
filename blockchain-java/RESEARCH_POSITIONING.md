# RESEARCH_POSITIONING.md

**Phase 0 deliverable — literature and gap analysis.**
Date of survey: 2026-07-19. Surveyor: automated search + primary-source verification.

---

## 0. How to read this document

Every claim below is tagged with its evidence level. This matters because the whole
program is gated on Phase 0 being honest:

| Tag | Meaning |
|---|---|
| **[VERIFIED]** | I fetched the primary source and read the relevant section. Quotes are from the source. |
| **[SNIPPET]** | Only seen via search-result summary. Treat as a lead, not a fact. Must be upgraded to [VERIFIED] before it is relied on in a proof or a related-work section. |
| **[UNVERIFIED]** | Asserted somewhere but not confirmed. Do not cite. |

**Survey completeness caveat:** this is a targeted search, not a systematic review. It
establishes *that a gap plausibly exists*; it does not prove novelty. Before any chapter
is submitted, each direction needs a proper systematic search (ACM DL / IEEE Xplore /
DBLP + forward-citation chase), because a single missed paper can invalidate a novelty
claim. Absence of evidence here is **not** evidence of absence.

Deliverable types used throughout: **[PROOF]**, **[BENCHMARK]**, **[ENGINEERING]**.

---

## 1. Direction 1 — Formal analysis of ML/reputation-informed leader election

### Prior work

**[VERIFIED] Reputation-Based Leader Election under Partial Synchrony (SWLE), arXiv:2512.12409.**
This is the most important finding in the entire survey and it **forces Direction 1 to
pivot.** The paper provides a protocol-independent abstraction with exactly the formal
vocabulary this direction needs:

- *Leader Uniqueness (safety):* "For any view v, there do not exist two distinct replicas
  j and k such that both can gather a quorum of votes (i.e., 2f+1 distinct votes) for
  conflicting leadership claims in v."
- *Timely Finalization (liveness):* "For any view v, every correct replica j finalizes
  leader_j(v) before or upon entering v."
- *γ-Guarantee (effectiveness):* a bound on the expected number of views in which all
  correct replicas agree on the same, correct leader.

Critically, it **defines** reputation-based leader election as one where "leader
determination relies solely on information available during consensus process or
consensus-generated on-chain information."

**What this means for us:** SWLE *assumes away* the HybridChain bug by definition. Its
reputation updates come from consensus-ordered rules (R1–R5); it never analyses what
happens when that restriction is violated. Confirmed by primary source: the paper has
**no discussion of nodes diverging because reputation derives from locally-observed
data**, and **no mention of machine learning, threat scoring, or anomaly detection**.

**[SNIPPET] HammerHead, arXiv:2309.12713** — reputation-based leader election for
DAG-BFT; reported to formally prove Safety, Liveness, and Leader Utilization. Must be
upgraded to [VERIFIED] before use.

**[SNIPPET] Shoal, arXiv:2306.03058** — DAG-BFT latency/robustness with leader
reputation. Same caveat.

### Positioning sentences

> **SWLE (2512.12409) does** define Leader Uniqueness / Timely Finalization / γ-Guarantee
> and prove a mechanism satisfying them *under the assumption that reputation derives
> solely from consensus-generated information*; **we do** characterise what breaks when
> that assumption is violated by locally-computed ML/anomaly signals, and give a
> construction that reintroduces ML signal without losing Leader Uniqueness; **this
> matters because** deployed systems (including this one) do feed local ML inference into
> leader weighting, and SWLE's framework is silent on that entire class.

> **HammerHead/Shoal do** apply leader reputation to DAG-BFT with formal guarantees;
> **we do** the partially-synchronous PBFT case with an explicitly non-deterministic
> (ML-derived) input; **this matters because** their reputation inputs are consensus-
> ordered by construction, so the divergence failure mode cannot arise there.

### Revised scope (the pivot)

Direction 1 is **narrower but stronger** than originally framed. Do **not** claim to be
first to formalise reputation-based leader election — SWLE has that. The remaining open
contribution is:

1. **[PROOF]** A counterexample/impossibility result: any leader-selection function whose
   input includes a locally-observed, non-consensus-ordered predicate violates SWLE's
   Leader Uniqueness. *Target SWLE's property definitions directly rather than inventing
   our own* — this makes the result immediately comparable and much more credible.
2. **[PROOF]** A "commit-then-use" construction: ML/threat signals may influence leader
   selection only after being committed as consensus-ordered events, restoring Leader
   Uniqueness. Inductive argument over block height.
3. **[BENCHMARK]** Measured divergence rate in a real implementation pre/post fix — SWLE
   is theory-only, so an empirical divergence measurement on a working JVM BFT stack is
   a genuine complement, not a duplicate.

**Falsifiable statement to hold ourselves to:**
> Under partial synchrony with N validators and up to f Byzantine, a leader-selection
> function whose weighting depends on any node-local predicate not ordered by consensus
> admits an execution in which two correct replicas finalise different leaders for the
> same view v (violating Leader Uniqueness); restricting the weighting inputs to
> consensus-ordered events is sufficient to prevent this.

### Tooling findings

**[VERIFIED, negative result] No 2026 TLA+/Apalache specification of PBFT with
non-standard leader-selection functions was found.** Apalache itself is the right tool
(symbolic, SMT-backed, faster than TLC for multi-process runs). Prior TLA+ verification
exists for HotStuff and Tendermint/PBFT generally, but not for our specific question. We
build the spec ourselves — this is a small, real contribution in itself.

**[SNIPPET] ByzzFuzz (gleissen.github.io/papers/byzzfuzz.pdf)** — randomized testing of
BFT algorithms via injected network and process faults. **This is the "adapt, don't
build" candidate the program asked for**; evaluate before writing a bespoke harness.

**[SNIPPET] Stabl (Middleware 2025)** — failure-sensitivity study injecting faults into
Algorand, Aptos, Avalanche, Redbelly, Solana. Useful methodology template for the
benchmark design.

---

## 2. Direction 2 — Signature-agnostic PQ threshold custody for mixed validator/IoT sets

### Prior work

**[VERIFIED] "Threshold Authorization Without Threshold Signatures: Signature-Agnostic MPC
Custody", arXiv:2607.08226, Dariia Porechna, submitted 2026-07-09, 25pp, cs.CR.**
The seed citation is **real and confirmed.** It proposes a dual-gate architecture
separating member authentication from threshold authorization: members sign with ordinary
signatures under any scheme, while the quorum produces a threshold seal from shared
secrets. Switching signature algorithm (ECDSA / SLH-DSA / ML-DSA) becomes "a key
rotation, not a protocol redesign." Provides information-theoretic security below
threshold.

### Two material risks to record now

1. **The paper is 10 days old at time of survey.** No citations, no peer review, no
   independent cryptanalysis. If its security argument has a flaw, any proof we build on
   top of it inherits that flaw. Mitigation: our contribution must state its dependency
   explicitly and, ideally, re-derive the below-threshold secrecy property rather than
   cite it as a black box.
2. Forward-citation search returned nothing (expected at 10 days), so we cannot yet tell
   whether the community considers it sound.

### Positioning sentence

> **Porechna (2607.08226) does** establish signature-agnostic threshold authorization with
> below-threshold information-theoretic secrecy for a homogeneous custody set; **we do**
> extend the analysis to a *heterogeneous* set where some members are IoT-class devices
> that cannot run lattice-based threshold signing, and measure it on constrained hardware;
> **this matters because** the paper's motivation (PQ migration where hash-based
> signatures resist threshold signing) is most acute exactly where devices are weakest,
> and that regime is unmeasured.

**Benchmark differentiator:** must run on Raspberry-Pi-class hardware, not a dev laptop.
A laptop benchmark here would be worthless — the entire claim is about constrained
members.

---

## 3. Direction 3 — Cost-bounded verifiable FL aggregation

### Prior work — **this direction needs the largest correction**

**[SNIPPET, high confidence] zkFL, arXiv:2310.02554** — "the aggregator provides a proof
per round demonstrating to clients that the aggregator executes the intended behavior
faithfully." **This is precisely the "zk-verified federated aggregation" idea that was
described earlier in this project as the rare, highest-leverage novelty. It already
exists and has since 2023.**

I want to be explicit about this because it was mischaracterised earlier in our work:
zk-proof-of-correct-aggregation is **established prior art, not an open gap.**

**[SNIPPET] Veriblock-FL (GitHub, ElmiraEbrahimi)** — blockchain FL with ZKPs for
verifiable *training and* aggregation. Open-source; check before implementing.

**[SNIPPET] arXiv:2503.13255** — ZKP-based consensus for blockchain-secured FL.
**[SNIPPET] arXiv:2511.21118** — trustless FL at edge scale; "compositional cryptographic
receipts" proving weighted aggregation, dropout recovery and Byzantine filtering in one
artifact. Closest neighbour to the edge-scale angle — **verify this one first**, it may
close more of the gap than expected.
**[SNIPPET] VOSA** — lightweight verifiable aggregation using bilinear pairings
*specifically to avoid* zk-SNARK overhead on constrained devices. Direct competitor to
the cost-bounded angle.

### Positioning sentence (narrowed)

> **zkFL / Veriblock-FL do** prove per-round aggregation correctness with zk-SNARKs, and
> **VOSA does** avoid ZKP cost via pairing-based verification; **we do** derive an explicit
> security-equivalence bound for *probabilistic* auditing (audit with probability p) and
> locate the empirical feasibility cliff on edge-class hardware; **this matters because**
> the existing work either assumes server-class proving cost or sidesteps ZK entirely —
> neither quantifies the tradeoff curve a deployer actually has to choose on.

**Honest risk:** this is the weakest-positioned of the four research directions. VOSA and
arXiv:2511.21118 may already occupy the space. **Do not write code for Direction 3 until
both are upgraded to [VERIFIED] and the positioning sentence survives.**

---

## 4. Direction 4 — Collusion-resistant swarm PUF attestation

### Prior work

**[VERIFIED] LPUF-AuthNet, arXiv:2410.12190 (Oct 2024, IEEE).** Combines tandem neural
networks with split learning so the challenge→response model is never wholly present on
either side; evaluated against SVM (RBF) and a 4-layer NN modeling attack.

**The gap here is verified and quotable — the strongest of the four.** From the paper's
own threat model:

> "We assume that Att can only listen to the untrusted wireless channel, and cannot hack
> the legitimate node or the verifier."

Confirmed by primary source: the paper has **no collusion analysis, no k-device threshold,
and addresses pairwise device–server authentication rather than device swarms.** The
adversary is a passive eavesdropper, explicitly not a coalition of compromised insiders.

### Positioning sentence

> **LPUF-AuthNet does** resist single-adversary ML modeling attacks by splitting the PUF
> model across device and verifier, under a threat model that explicitly excludes node
> compromise; **we do** model a coalition of k compromised devices pooling their split-model
> portions and derive the threshold below which collusion-resistance holds; **this matters
> because** the paper's own motivating deployment (billions of devices, fleets) is exactly
> the setting where "the attacker cannot hack any legitimate node" stops being credible.

**Falsifiable statement:**
> Under the LPUF-AuthNet split-learning construction with k of n devices fully compromised
> (adversary obtains their device-side model portions and CRP histories), the full PUF
> model remains unrecoverable to within accuracy ε for all k < k*, where k* is derived
> from [to be specified in Phase 4 step 2].

**Still to search before Phase 4:** collective/swarm attestation literature (SEDA/SANA
lineage and successors) — not yet surveyed. Direction 4's *swarm* half is currently
**[UNVERIFIED]** for novelty even though its *collusion* half is solid.

---

## 5. Direction 5 — Closed-loop IoT trust degradation

**No research claim. [ENGINEERING] only.** Correctly scoped as master's-level in the
program prompt.

Status note from prior audit of this repo (2026-07-19): the anomaly→reputation *scoring*
path already exists — `Blockchain`'s TELEMETRY handler calls
`DeviceLifecycleManager.recordDeviceActivity()` → `ReputationEngine.updateScore()`.
What genuinely does **not** exist is *enforcement*: no code anywhere bans, quarantines,
disconnects, or rate-limits a device based on its score. That enforcement path is the
real deliverable, plus a time-to-quarantine benchmark.

---

## 6. Phase 7 — mathematics-to-add register

Logged now so they are not forgotten; each is a small [PROOF] or [BENCHMARK] item.

| # | Item | Current state | Target |
|---|---|---|---|
| M1 | `ReputationEngine` constants `SUCCESS_INCREMENT=0.01`, `FAILURE_DECREMENT=0.05` | Arbitrary magic numbers | Model as Beta-belief update or EWMA; derive convergence rate + FP/FN tradeoff. **[PROOF]** |
| M2 | PBFT view-change timeout | Fixed constant (`timeoutMs = 15_000`) | Derive from a network-delay model; bound wasted view-changes vs false positives. **[PROOF]** |
| M3 | Leader weighting function (post-Phase-1) | Plausible, unanalysed | Game-theoretic: can a rational validator increase expected leader-share? Bound the gain. **[PROOF]** |
| M4 | DP ε accounting across FL rounds | ε applied per aggregation; **composition across rounds not verified** | Verify sequential/advanced composition is actually correct over a full training run. Known subtle bug class. **[PROOF]** |
| M5 | Fee market / difficulty adjustment | Heuristic step functions | Control-theoretic (PID-style) model with stability bound. **[PROOF]** |
| M6 | Byzantine-robust FL aggregator choice (Krum/median/trimmed-mean) | Implemented, untested against a formal breakdown point | State and verify the breakdown point empirically. **[BENCHMARK]** |

M4 is flagged as the highest-value of these: the DP implementation currently applies the
Gaussian mechanism once per aggregation with no cross-round privacy-budget accumulation
visible in the code. If ε does not compose, the stated privacy guarantee is simply false
over a multi-round run — that is a correctness bug, not just a missing proof.

---

## 7. Phase 0 exit status

| Direction | Gap status | Verdict |
|---|---|---|
| 1 — ML/reputation leader election | **Pivoted, verified** | Proceed. Narrowed to violation-characterisation + commit-then-use construction, targeting SWLE's property definitions. |
| 2 — PQ threshold custody | **Verified, seed confirmed** | Proceed with explicit dependency-risk note (seed is 10 days old, unreviewed). |
| 3 — Cost-bounded verifiable FL | **Weak — prior art denser than assumed** | **Hold.** Upgrade VOSA + arXiv:2511.21118 to [VERIFIED] before any code. |
| 4 — Collusion-resistant PUF | **Verified and quotable (collusion half)** | Proceed on collusion; survey swarm-attestation literature before claiming the swarm half. |
| 5 — Closed-loop trust | N/A ([ENGINEERING]) | Proceed anytime; no novelty claim. |

**Phase 0 is not fully closed.** Outstanding before it can be signed off:
- Upgrade all [SNIPPET] items to [VERIFIED] for directions 3 and 4.
- Run the swarm/collective-attestation search (Direction 4).
- Systematic (not targeted) search for each direction before any chapter is written.

---

## Sources

- [Reputation-Based Leader Election under Partial Synchrony (SWLE), arXiv:2512.12409](https://arxiv.org/abs/2512.12409)
- [HammerHead: Leader Reputation for Dynamic Scheduling, arXiv:2309.12713](https://arxiv.org/pdf/2309.12713)
- [Shoal: Improving DAG-BFT Latency and Robustness, arXiv:2306.03058](https://arxiv.org/pdf/2306.03058)
- [Threshold Authorization Without Threshold Signatures, arXiv:2607.08226](https://arxiv.org/abs/2607.08226)
- [zkFL: Zero-Knowledge Proof-based Gradient Aggregation for FL, arXiv:2310.02554](https://arxiv.org/pdf/2310.02554)
- [Veriblock-FL (GitHub)](https://github.com/ElmiraEbrahimi/Veriblock-FL)
- [ZKP-Based Consensus for Blockchain-Secured FL, arXiv:2503.13255](https://arxiv.org/pdf/2503.13255)
- [Trustless Federated Learning at Edge-Scale, arXiv:2511.21118](https://arxiv.org/pdf/2511.21118)
- [Verifiable secure aggregation (VOSA context), Discover Computing](https://link.springer.com/article/10.1007/s10791-025-09676-1)
- [LPUF-AuthNet, arXiv:2410.12190](https://arxiv.org/abs/2410.12190)
- [ByzzFuzz: Randomized Testing of BFT Algorithms](https://gleissen.github.io/papers/byzzfuzz.pdf)
- [Stabl: The Sensitivity of Blockchains to Failures (Middleware 2025)](https://gramoli.github.io/pubs/2025-Middleware-Stabl.pdf)
- [Apalache symbolic model checker](https://apalache-mc.org/)
- [Verification of HotStuff BFT Consensus with TLA+](https://www.shs-conferences.org/articles/shsconf/pdf/2021/04/shsconf_nid2020_01006.pdf)
