# J — Go / No-Go Decision and Scorecards

## 1. Decisions

| Use | Decision | Evidence that would change it |
|---|---|---|
| Research and education | **GO** | — |
| Local development and single-host testnet | **GO** | — |
| Controlled pilot (no real value, non-sensitive data) | **NO-GO** | C-1, C-2, C-11, C-16 fixed; live harness incl. Byzantine proposer/voter and kill-restart passing on ≥ 4 separate machines; measured throughput ≥ 10× pilot load; licence permits the pilot (C-22) |
| Production with real assets or sensitive data | **NO-GO** | everything above, plus an external security review with findings closed, a 30-day multi-operator pilot without divergence, tested backup/restore, upgrades and key rotation |
| Claiming technical superiority over an established alternative | **NO-GO** | the F §4 comparison against Fabric (and the database + transparency-log baseline) showing a measured advantage at equal correctness |

## 2. Engineering maturity (0–10)

| Area | Score | Evidence | Biggest reason it isn't higher |
|---|---|---|---|
| Protocol correctness | 5 | PBFT structure, NEW_VIEW justification, certificates (D §1) | restart amnesia (C-1); mixed-view counting unproven (C-9) |
| Consensus safety | 5 | S1–S7 hold in tests | S9 fails (reproduced); no formal model of the current protocol |
| Liveness and recovery | 4 | view change with back-off; seed redial; mesh | lying sync peer (C-2); proposer stalls of 135 s+ (C-4); no live run after the fixes |
| Transaction correctness | 7 | 30 audit PoCs, per-type validation, canonical execution order | no stateful supply-invariant model test |
| Cryptographic design | 6.5 | BouncyCastle, low-S, domain tags, hybrid `hq` binding | custom ZK proof; shared trust roots; no PQ in consensus |
| State determinism | 7.5 | canonical binary root v2, golden test, round-trip test | floats in WASM (C-5); one platform tested |
| Crash consistency | 7 | atomic block batch, refusal of inconsistent stores | no process-kill testing; consensus state not persisted |
| Smart-contract safety | 5 | gas metering, stack limits, revert semantics, capabilities | no developer tooling or ABI spec, float determinism, no external review |
| Network security | 5 | mTLS, bounded handshake, signed messages | plain-HTTP API; shared CA seed; WebSocket limits |
| IoT trust | 7 | attested provisioning, consent, device-signed readings, firmware signatures | reading replay (C-3); origin is not truth; no real device tested |
| AI evaluation quality | 2 | components exist and are kept advisory | no dataset, no metrics, DP without accounting |
| Performance evidence | 2 | first micro-measurements (F §2) | no load test; ≈ 4.6 tx/s ceiling |
| Operational readiness | 3.5 | Docker, CI, Dependabot, key-rotation document | no TLS, no dashboards, no backups, static validators |
| Documentation | 7 | explainer, deep dive, design notes, audits | some claims ahead of the code; template `SECURITY.md` |
| Maintainability | 5 | heavily commented fixes, CI | 2,000-line classes; single maintainer |
| Testing and formal verification | 7 | 724 tests, PoCs, randomised Byzantine tests, live harness | no formal model of current consensus; `App` entry point 0% coverage |

## 3. Competitive position (0–10)

| Dimension | Score | Reason |
|---|---|---|
| Technical differentiation | 5 | device-trust rules built in; nothing else is unique |
| Fit for the chosen application (G) | 6 | the domain model fits; throughput and multi-operator trust don't yet |
| Developer usability | 2 | no SDK, no OpenAPI, no contract tooling |
| Integration readiness | 2 | no TLS, no SDK, no standard interfaces (no EVM, no ABCI) |
| Production credibility | 1 | no deployment, no audit, no release |
| Ecosystem readiness | 1 | none |
| Cost of adoption | 2 | custom stack, single maintainer, restrictive licence |
| Evidence of demand | 0 | none |
| Evidence of measurable advantage | 0 | none measured |

These are not engineering failures. Most of them can only change through **users, operators,
auditors and partners**: a portfolio-quality engineering project and a competitive product are
different things.

## 4. What solo work can and cannot change

- **Solo-achievable:** every engineering-maturity score up to about 7–8; differentiation and fit up to about 7 (by building G §3 and running the comparison); developer usability up to about 5 (SDK, OpenAPI).
- **Needs others:** production credibility, ecosystem, evidence of demand, and an independent assurance of safety. These need pilot partners, an external review, and operators who aren't the author.
