# HybridChain — Independent Audit, 2026-10-24

| | |
|---|---|
| **Revision audited** | `12fb385` (local `main`; equals `origin/main` `fd5df74` minus `SECURITY.md`, plus 10 unpushed commits with fixes HC-087–HC-093) |
| **Remote** | https://github.com/marcamgad/Blockchain |
| **Method** | Read-only. Builds and probes ran in a separate clean checkout (git worktree at `12fb385`), so nothing in the working repository was modified apart from these documents. |
| **Environment** | Windows 11 amd64, 8 logical CPUs, OpenJDK 17.0.18 (Temurin), Maven 3.9.14, offline Maven repository |
| **Date of external research** | 2026-10-24 |

## Deliverables

| | Document | Content |
|---|---|---|
| A | [Executive verdict](A_EXECUTIVE_VERDICT.md) | Level, readiness, blockers, positioning |
| B | [Evidence inventory](B_EVIDENCE_INVENTORY.md) | What was inspected and run, with commands; what was not |
| C | [Findings register](C_FINDINGS_REGISTER.md) | Severity-ranked, deduplicated findings with evidence and acceptance criteria |
| D | [Protocol verification report](D_PROTOCOL_VERIFICATION.md) | Consensus specification, properties, adversarial coverage, formal status |
| E | [Comparative architecture](E_COMPARATIVE_ARCHITECTURE.md) | Against Besu, Fabric, Corda, CometBFT/Cosmos SDK, Polkadot SDK, Bitcoin Core, Geth, Bittensor, Akash, Chainlink, OpenZeppelin, transparency logs |
| F | [Benchmark plan and results](F_BENCHMARK_PLAN_AND_RESULTS.md) | Measured numbers (labelled), a protocol ceiling, and the benchmark program |
| G | [Strategic recommendation](G_STRATEGY.md) | Five directions scored; one primary, one complementary |
| H | [Remediation roadmap](H_REMEDIATION_ROADMAP.md) | Dependency-aware backlog with exit conditions |
| I | [Production-readiness checklist](I_PRODUCTION_READINESS_CHECKLIST.md) | Pass/fail per area |
| J | [Go / no-go](J_GO_NO_GO.md) | Decisions per use, and the evidence needed to change each |

## Conventions

- **Severity:** P0 critical · P1 high · P2 medium · P3 low · Info (rubric in C).
- **Confidence:** Confirmed (reproduced) · Strong evidence (code inspection) · Suspected · Unverified.
- **Evidence labels:** *[code]* source inspection, *[test]* repository test, *[probe]* audit probe run for this report, *[doc]* external documentation, *[inference]* auditor's reasoning.
- Earlier reports in `docs/` (deep dive, gap analysis, upgrade audit and plan) were written by the same assistant that wrote the HC-087–HC-093 fixes. This audit re-checks their claims against the code instead of taking them as evidence, and says where it agrees.
