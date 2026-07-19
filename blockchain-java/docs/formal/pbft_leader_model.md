# Formal Model: Leader Agreement in Reputation-/ML-Weighted PBFT

**Phase 1, steps 1–3.** Deliverables: the model (§1–§5), **[PROOF] Theorem 1** (§6,
violation), **[PROOF] Theorem 2** (§7, restoration).

**Positioning.** We deliberately adopt the property definitions of *Reputation-Based
Leader Election under Partial Synchrony* (SWLE, arXiv:2512.12409) rather than inventing
our own, so results here are directly comparable to that framework. SWLE **defines**
reputation-based election as relying "solely on information available during consensus
process or consensus-generated on-chain information," and therefore never analyses what
happens when that restriction is violated. Theorem 1 analyses exactly that violation;
Theorem 2 gives the minimal restriction that repairs it. See `RESEARCH_POSITIONING.md` §1.

---

## 1. System model

- **Validators.** A fixed set $V$, $|V| = N$, with identifiers totally ordered by a
  publicly-known relation $<_{id}$ (in the implementation: lexicographic byte order on the
  address string, via `Collections.sort`).
- **Faults.** At most $f$ validators are Byzantine, $N \ge 3f+1$. A validator that is not
  Byzantine is **correct**.
- **Synchrony.** Partial synchrony in the GST sense (Dwork–Lynch–Stockmeyer): there exists
  an unknown Global Stabilisation Time $\mathrm{GST}$ and a known bound $\Delta$ such that
  every message sent at time $t \ge \mathrm{GST}$ between correct validators arrives by
  $t + \Delta$. Before $\mathrm{GST}$, message delay is unbounded.
- **Clocks.** Each validator $p$ has a local clock. We assume **no** global clock and **no**
  bound on clock skew before GST. Local timers therefore fire at times that are *not*
  agreed between validators. This assumption is essential to Theorem 1 and is the weakest
  possible: Theorem 1 does not even require clock drift, only differing message-arrival
  times.
- **Views.** Consensus proceeds in views $v = 0, 1, 2, \dots$. Each view has a designated
  leader.

## 2. Events

The central modelling decision — and the distinction SWLE's definition presupposes but
does not analyse — is between two kinds of event.

**Definition 2.1 (Consensus-ordered event).** An event $e$ is *consensus-ordered* if its
occurrence and its position in a total order are agreed by every correct validator as a
consequence of the consensus protocol itself. Formally: $e \in E_c$ iff there exists a
committed block $B$ (agreed by a quorum of $2f+1$) such that $e$ is derivable as a pure
function of the committed chain prefix ending at $B$.

*Examples in this implementation:* a block commit (`markCommitted`), on-chain slashing
recorded in committed state.

**Definition 2.2 (Locally-observed event).** An event $e$ is *locally-observed* if its
occurrence at validator $p$ depends on $p$'s local state, local clock, or local message
arrival times, and is not (yet) a function of any committed chain prefix. Write $e \in
E_\ell$.

*Examples in this implementation:* expiry of $p$'s local view-change timer; the output of
$p$'s local ML inference (`PredictiveThreatScorer`); wall-clock inter-arrival measurements.

**Definition 2.3 (History).** $H_p(v)$ is the sequence of events validator $p$ has applied
to its reputation state before entering view $v$. We write $H_p(v)|_{E_c}$ for its
restriction to consensus-ordered events.

**Observation 2.4.** For $e \in E_c$, all correct $p,q$ eventually agree that $e$ occurred
and on its order. For $e \in E_\ell$, no such agreement is implied — indeed under partial
synchrony before GST, two correct validators may permanently disagree on whether a given
timeout event occurred at a given view.

## 3. The leader-selection function (faithful abstraction)

The implementation's `PBFTConsensus.selectLeader(long view)` is abstracted as follows.
Let $R_p : V \to \mathbb{R}_{\ge \rho}$ be $p$'s reputation map ($\rho = $ `REP_MIN` $= 0.01$),
and $T_p : V \to [0,1]$ its local threat score.

1. Sort $V$ by $<_{id}$ giving $(u_1, \dots, u_N)$.
2. Weights:
$$
w_p(u_i) \;=\;
\begin{cases}
0 & \text{if } T_p(u_i) > \tau \quad (\tau = 0.7)\\[2pt]
\max(\rho,\, R_p(u_i)) & \text{otherwise}
\end{cases}
$$
3. $W_p = \sum_i w_p(u_i)$.
4. A deterministic view-derived fraction $\phi(v) \in [0,1)$:
   $\phi(v) = \big((v \cdot a + c) \bmod 2^{63}\big) / (2^{63}-1)$ with the implementation's
   LCG constants $a = 6364136223846793005$, $c = 1442695040888963407$. **$\phi$ depends only
   on $v$** — it is identical at every validator.
5. Target $\theta_p(v) = \phi(v)\cdot W_p$.
6. Return the first $u_k$ in sorted order with $\sum_{i \le k} w_p(u_i) \ge \theta_p(v)$.

**Lemma 3.1 (Purity).** $\mathrm{leader}_p(v)$ is a deterministic function of
$(v, (u_1..u_N), w_p)$ alone. *Proof:* immediate from steps 1–6; no other input is read. $\square$

**Corollary 3.2.** If $w_p \equiv w_q$ (identical weight vectors) then
$\mathrm{leader}_p(v) = \mathrm{leader}_q(v)$ for every $v$. Conversely, divergence
requires $w_p \not\equiv w_q$. **All subsequent argument therefore reduces to whether the
weight vectors can differ between correct validators.**

## 4. Target property

**Definition 4.1 (Leader Agreement).** For every view $v$ and all correct $p, q$:
$\mathrm{leader}_p(v) = \mathrm{leader}_q(v)$.

This is the precondition for SWLE's *Leader Uniqueness* safety property ("for any view $v$
there do not exist two distinct replicas both able to gather a quorum for conflicting
leadership claims"): if correct validators compute different leaders they will vote for
conflicting leadership claims, and with $2f+1$ correct validators split across two
candidates neither obtains a quorum — liveness stalls — or, worse, a partitioned quorum
certifies conflicting proposals.

## 5. What the implementation actually does

Empirically established by reading `PBFTConsensus.java` (2026-07-19):

| Path | Line | Event class | Feeds `selectLeader`? |
|---|---|---|---|
| `markCommitted` → `updateReputation(proposer, +0.02)` | 609 | $E_c$ | yes |
| double-sign slash → `updateReputation(id, −0.50)` | 529, 564 | $E_c$ (message-evidenced) | yes |
| **`triggerViewChange` → `updateReputation(faultyLeader, −0.10)`** | **643** | **$E_\ell$** | **yes** |
| `updateReputation` → `PredictiveThreatScorer.recordActivity` | 489 | $E_\ell$ (uses local wall clock) | yes, via $T_p > \tau$ |

The decisive path is line 643. `triggerViewChange()` is invoked from a **local timer**:

```java
currentTimerTask = timer.schedule(() -> {
    triggerViewChange();          // line 313
}, timeoutMs, TimeUnit.MILLISECONDS);
```

The reputation penalty is applied **at the instant this validator's own timer expires** —
strictly before, and independently of, any $2f+1$ view-change quorum
(`processViewChange` is only reached later, at line 628). The method's own contract says:

> "This must only be called at CONSENSUS BOUNDARIES (block commit, view change) to ensure
> all nodes update reputation identically." (lines 481–482)

The code therefore **states the correct invariant and then violates it**: a local timeout
is not a consensus boundary. This is the defect Theorem 1 formalises.

A second, independent channel exists: `PredictiveThreatScorer` derives
`timeSinceLastActivity` from `System.currentTimeMillis()` at the observing node and can
zero a validator's weight via $T_p > \tau$. Either channel alone suffices for Theorem 1.

---

## 6. [PROOF] Theorem 1 — Leader Agreement fails under locally-observed inputs

**Theorem 1.** *Let leader selection be as in §3 and let reputation updates include any
event in $E_\ell$. Then there exists an execution, containing no Byzantine validators and
no clock drift, in which two correct validators compute different leaders for the same
view. Hence Leader Agreement (Def. 4.1) — and therefore SWLE Leader Uniqueness — does not
hold.*

**Proof.** By explicit construction.

*Configuration.* $N = 4$, $f = 1$, all four validators correct. Identifiers sorted as
$(A,B,C,D)$. All threat scores $0$ (the threat channel is not needed). Initial reputation
$R(u) = 1.0$ for all $u$, identical at every validator. Timeout bound $\Delta_{to} =$
`timeoutMs`.

*Execution.* Consider view $v$ with leader $A$, and let $t=0$ be when correct validators
enter $v$. All events occur before GST, so message delay is unbounded and the environment
may schedule delivery adversarially without violating partial synchrony.

1. $A$ (correct) broadcasts its PRE-PREPARE at $t = 0$.
2. The environment delivers $A$'s message to $p_2$ at $t = \Delta_{to}/2$ (in time) and to
   $p_1$ at $t = 2\Delta_{to}$ (late).
3. At $t = \Delta_{to}$, $p_1$'s local timer expires. `triggerViewChange()` executes at
   $p_1$, applying $\texttt{updateReputation}(A, -0.10)$ **locally**.
4. $p_2$'s timer does not expire (it received the proposal and called `resetTimer`).

*Resulting weight vectors.* No consensus event has occurred, so:
$$
w_{p_1} = (0.9,\,1,\,1,\,1),\quad W_{p_1} = 3.9 \qquad
w_{p_2} = (1,\,1,\,1,\,1),\quad W_{p_2} = 4.0
$$

*Selection.* Both validators evaluate §3 step 6 with the **same** $\phi \equiv \phi(v')$
for the view $v'$ being selected (Lemma 3.1: $\phi$ depends only on the view number).

- $p_1$ returns $A$ $\iff \phi \cdot 3.9 \le 0.9 \iff \phi \le 0.9/3.9 = 0.230769\ldots$
- $p_2$ returns $A$ $\iff \phi \cdot 4.0 \le 1.0 \iff \phi \le 1.0/4.0 = 0.25$

Therefore for every
$$
\boxed{\;\phi \in (\,0.230769\ldots,\; 0.25\,]\;}
$$
$p_2$ selects $A$ while $p_1$ selects $B$: two correct validators, same view, different
leaders. $\blacksquare$

**Corollary 6.1 (quantitative divergence rate).**

> **Correction notice.** An earlier version of this corollary claimed
> $\Pr \approx 1.92\%$, obtained by counting only the *first* selection boundary
> ($A$ vs $B$). Measurement against the real implementation returned $3.8470\%$ — almost
> exactly twice the prediction — which falsified that analysis. The error: the cumulative
> scan of §3 step 6 can diverge at **every** index $k$, not only $k=0$. The corrected
> derivation follows. This is recorded rather than silently patched, per the program's
> ground rules.

Divergence occurs at index $k$ precisely when the two validators' scans cross their
thresholds on opposite sides, i.e. for
$\phi \in \big(\tfrac{S_k - \delta}{W-\delta},\ \tfrac{S_k}{W}\big]$,
where $S_k = \sum_{i \le k} w_{p_2}(u_i)$ is the unpenalised cumulative weight,
$W = W_{p_2}$, and $\delta$ is the penalty magnitude applied by $p_1$ alone (so
$W_{p_1} = W - \delta$ and every $p_1$ cumulative sum is $S_k - \delta$, the penalty
falling on the sorted-first validator). The width of that interval is
$$
g_k \;=\; \frac{S_k}{W} - \frac{S_k-\delta}{W-\delta}
\;=\; \frac{\delta\,(W - S_k)}{W\,(W-\delta)} .
$$
Since the intervals are disjoint and $\phi$ is equidistributed,
$$
\Pr[\text{divergence}] \;=\; \sum_{k=0}^{N-1} g_k
\;=\; \frac{\delta}{W(W-\delta)}\sum_{k=0}^{N-1}\big(W - S_k\big).
$$
Specialising to the uniform case ($w(u)=1$ for all $u$, so $W=N$ and $S_k = k+1$), we get
$\sum_{k=0}^{N-1}(N-(k+1)) = \tfrac{N(N-1)}{2}$, hence the closed form
$$
\boxed{\;\Pr[\text{divergence}] \;=\; \frac{\delta\,(N-1)}{2\,(N-\delta)}\;}
$$

**Empirical validation.** Measured against the actual `selectLeader` implementation over
200 000 views (`LeaderAgreementCounterexampleTest`):

| $N$ | $\delta$ | Predicted | Measured | Δ |
|---|---|---|---|---|
| 4 | 0.10 | 3.8462 % | **3.8470 %** | +0.0008 pp |
| 7 | 0.10 | 4.3478 % | **4.3495 %** | +0.0017 pp |

Both residuals are within the discrepancy expected of the LCG, at two independent values
of $N$. The first divergent view for $N=4$ is $v = 11$ — a concrete witness to Theorem 1.

Note the rate is **increasing in $N$** (as $N\to\infty$, $\Pr \to \delta/2$): larger
validator sets do not dilute the defect. This is the quantity the Phase 1 step 6 benchmark
must reproduce in a live multi-node deployment, where it becomes a *lower* bound — real
deployments accumulate many unilateral penalties, not one.

**Remark 6.2 (strength of the result).** The construction uses **no Byzantine validator,
no clock drift, and no message loss** — only differing arrival times, which partial
synchrony explicitly permits before GST. The defect is therefore not an artifact of a
strong adversary; it is reachable in ordinary operation on a congested network.

**Remark 6.3 (the threat-score channel).** An analogous construction drives divergence
through $T_p$: if $p_1$'s local ML inference yields $T_{p_1}(A) > \tau$ while $p_2$'s does
not, then $w_{p_1}(A) = 0 \ne w_{p_2}(A)$ and Corollary 3.2 applies. Because
`PredictiveThreatScorer` scores from local wall-clock inter-arrival times, this channel is
active whenever network timing differs across nodes.

---

## 7. [PROOF] Theorem 2 — Restriction to consensus-ordered events restores agreement

**Theorem 2.** *Suppose (C1)–(C4) below hold. Then Leader Agreement (Def. 4.1) holds: for
every view $v$ and all correct $p,q$, $\mathrm{leader}_p(v) = \mathrm{leader}_q(v)$.*

- **(C1) Consensus-ordered inputs only.** Every event applied to $R_p$ (and to any input of
  $w_p$) lies in $E_c$: it is a pure function of a committed chain prefix.
- **(C2) Canonical application order.** Correct validators apply the events of $E_c$ in the
  order induced by the committed chain (block height, then intra-block index).
- **(C3) Deterministic arithmetic.** The reputation update and the weight accumulation are
  evaluated in an arithmetic that is deterministic and associative across validators —
  e.g. fixed-point/integer arithmetic, or IEEE-754 with a fixed evaluation order.
- **(C4) Agreed validator set.** $V$ and $<_{id}$ are themselves determined by committed
  state.

**Proof.** By induction on the view number $v$, with the invariant
$$
\mathcal{I}(v):\quad \forall\ \text{correct } p,q:\ w_p = w_q \text{ at the time } v \text{ is entered.}
$$

*Base case $v = 0$.* At genesis every correct validator initialises $R(u)$ to the same
constant for all $u \in V$ (implementation: `initReputation`, all $1.0$), and $V, <_{id}$
come from genesis state (C4). No event has been applied. Hence $w_p = w_q$ and
$\mathcal{I}(0)$ holds.

*Inductive step.* Assume $\mathcal{I}(v)$. Let $\mathcal{E}(v)$ be the set of events applied
by any correct validator between entering $v$ and entering $v+1$.

By (C1), $\mathcal{E}(v) \subseteq E_c$; so by Definition 2.1 each $e \in \mathcal{E}(v)$ is
a function of a committed chain prefix. Two correct validators that both enter $v+1$ have,
by the agreement property of the underlying consensus (quorum intersection: any two
quorums of size $2f+1$ over $N \ge 3f+1$ share at least one correct validator), committed
the *same* chain prefix over the interval. Hence they apply the *same set*
$\mathcal{E}(v)$.

By (C2) they apply that set in the *same order*; by (C3) applying the same multiset of
updates in the same order to equal starting values yields equal results. Therefore
$R_p = R_q$ after the interval, and since by (C1) no other input (in particular no local
threat score) contributes to $w$, we get $w_p = w_q$ — i.e. $\mathcal{I}(v+1)$.

*Conclusion.* $\mathcal{I}(v)$ holds for all $v$. By Corollary 3.2, equal weight vectors and
the shared $\phi(v)$ give $\mathrm{leader}_p(v) = \mathrm{leader}_q(v)$. $\blacksquare$

**Remark 7.1 — (C3) is not a technicality.** The implementation stores reputation as
`double` and accumulates weights with floating-point `+`. IEEE-754 addition is **not
associative**, so even under (C1) and identical event *sets*, two validators applying them
in different orders can obtain reputation values differing in the last ulp. The
comparison $\sum_{i\le k} w(u_i) \ge \theta$ can then tip differently and select different
leaders. (C2)+(C3) are therefore both load-bearing, and the honest engineering conclusion
is to move reputation to fixed-point integer arithmetic rather than rely on ordering
alone. **This is a distinct, previously-unlogged defect** and is recorded as such below.

**Remark 7.2 — what Theorem 2 does *not* claim.** It establishes *agreement* on the leader,
not that the elected leader is correct, nor liveness. SWLE's *Timely Finalization* and
*γ-Guarantee* are separate properties; we have not proved them here, and no claim about
them should be made from this document.

---

## 8. Assumptions, limitations, open items

1. **Not machine-checked yet.** §6 and §7 are rigorous-informal, hand-written proofs. They
   are Phase 1 step 4's obligation to corroborate by model checking (Apalache), which is
   *not yet done*. Until then they carry the status of careful argument, not verified fact.
   Theorem 1 additionally has executable corroboration against the real implementation
   (`LeaderAgreementCounterexampleTest`), and Corollary 6.1's closed form is validated at
   $N \in \{4,7\}$ — but an executable witness is evidence for the existential claim, not
   a proof of the universal one.
1b. **Corollary 6.1 was wrong on first derivation** and was corrected only because it was
   tested. Treat every remaining unvalidated analytic claim in this document with the same
   suspicion: §7's Theorem 2 has *no* executable counterpart beyond a single agreement
   check and is the most likely place for a similar error to be hiding.
2. Theorem 2's (C1) requires that the view-change penalty be moved to a consensus boundary.
   A view change *does* become consensus-ordered once $2f+1$ VIEW_CHANGE messages form a
   quorum (`processViewChange`); the fix is to apply the penalty there, not in
   `triggerViewChange`.
3. The threat-score channel (Remark 6.3) cannot be repaired by relocation alone: local ML
   inference is inherently in $E_\ell$. Restoring (C1) requires the *commit-then-use*
   construction — the ML verdict must first be committed on-chain, after which it is a
   function of the chain prefix and admissible. Designing and proving that construction is
   the substantive research contribution flagged in `RESEARCH_POSITIONING.md` §1.
4. Corollary 6.1's ≈1.92% assumes LCG equidistribution; the LCG's low-order-bit structure
   may perturb this. The benchmark must therefore measure the empirical rate rather than
   assume the analytic one.
5. $N=4$ is used for concreteness in Theorem 1; the construction generalises to any
   $N \ge 4$ (the divergence interval changes width but remains non-empty whenever a single
   penalty changes $W$).

## 9. Consequences for Phase 1 step 5 [ENGINEERING]

Derived directly from the proofs — each is a change the proofs *require*, not a preference:

| # | Change | Justified by |
|---|---|---|
| E1 | Move `updateReputation(faultyLeader, REP_MISSED_SLOT)` out of `triggerViewChange()` (line 643) and into `processViewChange()` after the $2f+1$ quorum. | Theorem 2 (C1); Theorem 1 is exactly this path |
| E2 | Remove `PredictiveThreatScorer` from `selectLeader`'s weight computation, **or** gate it behind committed-on-chain threat verdicts. | Theorem 2 (C1); Remark 6.3 |
| E3 | Replace `double` reputation with fixed-point integer arithmetic. | Theorem 2 (C3); Remark 7.1 — **new defect, not in the original S1 findings** |
| E4 | Apply reputation events in canonical committed order. | Theorem 2 (C2) |

E3 is newly identified by this analysis and should be logged alongside the known
S1-01/S1-02/S1-03 items.

## 10. Model checking (Phase 1 step 4) — partially executed

### 10.1 Artifacts

- **`docs/formal/LeaderSelect.tla`** — TLA+ module encoding §3 (scaled-integer reputation;
  the LCG fraction $\phi(v)$ replaced by a universally-quantified rational $kk/\mathrm{Denom}$,
  a faithful over-approximation since any real $\phi$ falls in some $1/\mathrm{Denom}$ bucket).
- **`docs/formal/LeaderSelect_Broken.cfg`** — `Broken = TRUE`: `LocalPenalty(n,i)` enabled
  independently at a single node (models `triggerViewChange()` on one node's timer).
- **`docs/formal/LeaderSelect_Fixed.cfg`** — `Broken = FALSE`: `CommitPenalty(i)` applies to
  every correct node atomically (models a $2f+1$ boundary).
- Invariant in both: `LeaderAgreement` (Def. 4.1), quantified over all fraction buckets.

### 10.2 Status — executed

The spec **has** been model-checked with TLC, and independently corroborated by a
hand-rolled exhaustive enumeration.

- **Tool:** TLC2 version 2.19 of 08 August 2024 (rev 5a47802), from `tla2tools.jar`
  (official TLA+ release), SHA-256
  `936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88`, run on
  Eclipse Adoptium JDK 17.0.18.
- **Command:** `java -cp tla2tools.jar tlc2.TLC -config <cfg> LeaderSelect.tla`
  (see `run_tlc.sh`).
- TLC exit code `0` = no error; `12` = invariant violated.
- **Independent check:** `LeaderSelectModelCheckTest` enumerates the same finite model in
  Java. It is *not* TLC — it is a separate implementation of the same semantics, so
  agreement between the two is meaningful cross-validation (a transcription error in the
  `.tla` would be caught by disagreement).

### 10.3 Results

| Instantiation | $N$ | MaxSteps | Denom | TLC verdict | TLC distinct states | Java enumerator states | Agree? |
|---|---|---|---|---|---|---|---|
| **Broken** | 4 | 2 | 40 | **LeaderAgreement violated** (exit 12) | 2 (stops at violation) | 45 (explores fully) | ✔ both violate |
| **Broken** | 7 | 2 | 100 | **LeaderAgreement violated** (exit 12) | 2 (stops at violation) | 120 (explores fully) | ✔ both violate |
| Fixed | 4 | 3 | 40 | **No error found** (exit 0) | **35** | **35** | ✔ exact match |
| Fixed | 7 | 4 | 100 | **No error found** (exit 0) | **330** | **330** | ✔ exact match |

The Fixed-configuration state counts agree **exactly** (35 and 330) between TLC and the
independent Java enumerator, which is strong evidence both encode the same model. The
Broken counts differ only because TLC halts at the first violation while the enumerator
continues exhausting the space.

**TLC's counterexample for Broken (N=4)** — verbatim from the trace:

```
State 1: <Initial predicate>
/\ rep = (p1 :> <<100, 100, 100, 100>> @@ p2 :> <<100, 100, 100, 100>>)
/\ steps = 0

State 2: <Next line 94 ...>
/\ rep = (p1 :> <<90, 100, 100, 100>> @@ p2 :> <<100, 100, 100, 100>>)
/\ steps = 1
```

**Three independent methods now agree on the same counterexample**: the hand construction
of §6, the Java exhaustive enumeration, and TLC. Each produces the state in which $p_1$ has
unilaterally penalised the sorted-first validator by $0.10$ while $p_2$ has not. TLC reaches
it in a single transition from the initial state — the defect is not merely reachable, it
is *one local timeout away* from a healthy chain.

**A note on a false alarm.** The first Fixed run reported `Deadlock reached` (exit 11).
This was **not** an invariant violation: it is an artifact of bounding the model with
`steps < MaxSteps`, which leaves terminal states with no successor. Adding
`CHECK_DEADLOCK FALSE` (termination by bound is intentional here) yields the clean
`No error has been found`. Recorded because mistaking that message for a real finding
would have been an easy and material error.

**Counterexample returned for Broken (N=4):**
$$
\mathrm{rep}_{p_1} = (90,100,100,100),\quad
\mathrm{rep}_{p_2} = (100,100,100,100),\quad kk = 10 .
$$

This is **exactly** §6's construction, found independently by exhaustive search: $p_1$ has
unilaterally penalised the sorted-first validator by $0.10$ and $p_2$ has not. The
violating bucket $kk=10$ with $\mathrm{Denom}=40$ gives $\phi = 0.25$ — precisely the upper
endpoint of the divergence interval $(0.230769\ldots,\,0.25]$ derived by hand in Theorem 1.
Verifying by hand: $p_1$ needs $90\cdot 40 \ge 10\cdot 390$, i.e. $3600 \ge 3900$ — false,
so $p_1$ advances to $B$; $p_2$ needs $100\cdot 40 \ge 10\cdot 400$, i.e. $4000 \ge 4000$ —
true, so $p_2$ selects $A$. Different leaders, same view.

The hand proof and the mechanical search agree on both the shape and the exact numeric
boundary of the counterexample. That is meaningful independent corroboration of Theorem 1.

### 10.4 Limits of what step 4 establishes

Step 4's success criterion is met, but it is important to be precise about what it does
**not** establish:

1. **The Fixed results are bounded.** MaxSteps 3–4, $N \in \{4,7\}$, two nodes, fraction
   resolution $1/40$ and $1/100$. "No error found" means *no violation exists in that finite
   space* — it is evidence for Theorem 2 within the horizon, **not** proof of unbounded
   correctness. The unbounded claim rests entirely on Theorem 2's hand-written induction
   (§7), which remains unverified by machine.
2. **Only two correct nodes are modelled.** Leader Agreement is a property over all pairs
   of correct nodes; two suffices to exhibit a violation, but a fuller model would quantify
   over larger correct sets.
3. **Byzantine behaviour is not modelled at all.** The model contains only correct nodes —
   which *strengthens* Theorem 1 (no adversary needed) but means nothing here speaks to
   safety under actual Byzantine faults.
4. **Reputation is integer-valued in the model.** This deliberately sidesteps Remark 7.1 /
   defect E3 (IEEE-754 non-associativity). The model therefore cannot detect that defect,
   and its absence from these results must not be read as evidence that E3 is benign.
5. **The abstraction is not mechanically linked to the Java code.** `LeaderSelect.tla`
   encodes §3, which I asserted is a faithful abstraction of `selectLeader` by reading the
   source. Nothing verifies that correspondence — a divergence between the model and the
   implementation would be invisible to both TLC and the enumerator. The
   `LeaderAgreementCounterexampleTest` (which runs against the *real* `selectLeader`) is
   what partially closes this gap, and it agrees.
