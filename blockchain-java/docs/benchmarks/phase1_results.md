# [BENCHMARK] Phase 1 step 6 — leader divergence, pre-fix vs post-fix

**Harness:** `src/test/java/com/hybrid/blockchain/consensus/LeaderDivergenceBenchmark.java`
**Raw data:** `phase1_leader_divergence_pre-fix.csv`, `phase1_leader_divergence_post-fix.csv`
**Seed:** 20260719 (fixed). **Rounds:** 5000 per cell. **Date:** 2026-07-19.

## Reproduce

```bash
# post-fix (current build)
mvn -o test -Dtest=LeaderDivergenceBenchmark -Dbench.variant=post-fix

# pre-fix requires temporarily restoring the pre-E1 behaviour in
# PBFTConsensus.triggerViewChange() — see "Methodology correction" below.
```

## Result

Divergence = fraction of rounds in which correct replicas did **not** all elect the same
leader for the next view.

| N | timeout skew | pre-fix | post-fix | change |
|---|---|---|---|---|
| 4 | 0.00 | 0.0000 % | 0.0000 % | — |
| 4 | 0.01 | 1.8000 % | **0.0000 %** | eliminated |
| 4 | 0.05 | 2.5200 % | **0.0000 %** | eliminated |
| 4 | 0.10 | 2.8200 % | **0.0000 %** | eliminated |
| 4 | 0.25 | 1.4200 % | **0.0000 %** | eliminated |
| 7 | 0.00 | 0.0000 % | 0.0000 % | — |
| 7 | 0.01 | 2.8200 % | **0.0000 %** | eliminated |
| 7 | 0.05 | 4.7200 % | **0.0000 %** | eliminated |
| 7 | 0.10 | **8.1800 %** | **0.0000 %** | eliminated |
| 7 | 0.25 | 7.2800 % | **0.0000 %** | eliminated |

**Control:** at `timeout skew = 0.00` both builds show exactly 0 % — the harness does not
manufacture divergence, and consensus-ordered commit credits alone never split the leader.
That is direct empirical support for Theorem 2 condition (C1).

## Two observations worth recording

**1. Divergence is non-monotonic in the timeout rate.** For N=4 it rises to 2.82 % at 10 %
skew then *falls* to 1.42 % at 25 %. This is a real effect, not noise: divergence requires
the timing-out replicas to be a *split* of the validator set. At very high timeout rates
the subset frequently covers most or all replicas, which penalises the same leader
everywhere and keeps the maps in agreement. The dangerous regime is therefore
*intermittent* skew — the ordinary case on a congested network — not total breakdown.

**2. Throughput must NOT be read as a regression.** `selectLeader` throughput measured
2 301 249 ops/s pre-fix and 1 701 281 ops/s post-fix in these two runs. Earlier runs of the
same build measured 1 609 498 and 1 801 523 ops/s. **The run-to-run spread (1.6–2.3 M) is
larger than the apparent pre/post difference**, so these single samples do not support any
claim about the fix's cost. A defensible throughput comparison needs repeated trials with
variance reported (JMH or n≥10 runs). Recorded here as *unmeasured*, not as "no cost".

## Methodology correction (recorded per programme ground rules)

The first attempt at this benchmark produced **identical pre- and post-fix numbers**, which
initially looked like the fix had failed. It had not — the harness was invalid.

The original harness simulated a local timeout by calling `updateReputation(leader,
REP_MISSED_SLOT)` **directly**. That reproduces the *effect* of the bug but bypasses
`triggerViewChange()` — the exact code path E1 modifies — so the benchmark was structurally
blind to the fix. It was measuring its own simulation, not the system.

The harness now invokes `triggerViewChange()`, the production path, and the pre-fix baseline
was re-captured against a temporarily restored pre-E1 build. The numbers above are from the
corrected harness.

*Generalisable lesson: a benchmark that simulates the symptom rather than exercising the
mechanism can validate a fix that does nothing. The identical-numbers result was the only
signal that anything was wrong.*

## Residual risk not closed by E1

`updateReputation` remains **public**. E1 removes the one production caller that violated the
consensus-ordered contract, and the contract is now documented on the method, but nothing
*enforces* it — any future caller can reintroduce Theorem 1's defect. Candidate hardening:
make the method package-private or route all updates through a single committed-event
applier. Logged as open.

## Status of the other engineering items

| Item | State | Evidence |
|---|---|---|
| **E1** move view-change penalty to the 2f+1 quorum boundary | done | this benchmark (8.18 % → 0 %) |
| **E2** remove `PredictiveThreatScorer` from `selectLeader` weights | done | code; scorer retained for monitoring only |
| **E3** fixed-point integer reputation (was `double`) | done | code; **not independently benchmarked** — the float defect is not what this harness measures |
| **E4** canonical ordering of reputation events | partially | integer sums are associative, but the `max(REP_MIN, ·)` clamp remains order-dependent; holds automatically while all inputs are chain-derived, but is not enforced |
