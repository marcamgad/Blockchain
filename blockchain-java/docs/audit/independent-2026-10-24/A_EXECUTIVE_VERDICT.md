# A — Executive Verdict

## One paragraph

HybridChain is a **single-developer research prototype of a permissioned PBFT ledger with
first-class IoT device identity**. It has an unusually strong verification habit for its size:
724 passing tests, 30 audit proof-of-concept tests, a live Docker fault-injection harness and CI.
It also has a real, narrow idea: device lifecycle and device-signed telemetry enforced by the ledger
itself. But it is **not a production candidate**, and it is not competitive with Hyperledger
Fabric, Besu or CometBFT on any axis an infrastructure buyer measures first. Its consensus has
just had a critical class of bugs fixed (HC-087), and that fix has a residual hole when a validator
restarts (HC-095, reproduced). Its throughput ceiling under its own production settings is about
**4.6 transactions per second** (computed from measured sizes, F §2). It has never run on more than
one machine, never been load-tested, and never been reviewed by anyone independent.

## Classification

| Use | Verdict |
|---|---|
| Research / education | **Suitable**, and a good portfolio piece |
| Local development / testnet | **Suitable** on a single host |
| Controlled pilot (no real value, non-sensitive data) | **Not yet**: needs HC-095 and the other P1 items in C, a multi-machine run, TLS |
| Production with real assets or sensitive data | **No** |
| Claim of technical superiority over an established system | **No**: there is no comparative evidence (F) |

## Critical blockers (in order)

1. **Consensus safety under restart is unproven, and one hole is reproduced (HC-095).** Vote state and the view number exist only in memory. A restarted validator will sign a conflicting vote, which the jailing mechanism then punishes. CometBFT persists exactly this state, its "last signed" record, for this reason.
2. **Consensus has never run as separate machines,** and the post-fix rules have never run live. The live harness passed before HC-087 to HC-093; their behaviour under real message passing is untested.
3. **A throughput ceiling of about 4.6 tx/s** in the production configuration: hybrid post-quantum transactions of 7.6 KB, a 2 MB block limit, one block every 60 s. On top of that, every block rewrites the whole state, which costs O(state).
4. **No transport security on the public API** (plain HTTP), shared network-wide secrets (one CA seed, one JWT secret), and validator keys in environment variables.
5. **No independent review** of consensus, cryptography (including a custom Schnorr ownership proof) or storage.

## Most credible competitive advantage

**Device trust built into the ledger's state machine.** The ledger enforces manufacturer-attested
provisioning, activation that needs the device's signed consent, owner-only lifecycle changes,
manufacturer-signed firmware records, and device-signed readings checked against block time, then
exposes the latest authenticated reading deterministically to contracts. On Fabric or Besu all of
this is application code each adopter writes. It's a real differentiator, but a thin one: it's a
library-sized advantage, not a protocol-sized one. It would have to be shown to cut integration
effort and errors, not just asserted.

## Why a company would reject it today

- **The licence forbids it.** `LICENSE` permits only non-commercial use and forbids publishing modified versions (C-22). A company can't legally pilot it without a separate agreement. That ends most evaluations before any technical question is asked.
- One maintainer, no releases, no security contact (`SECURITY.md` is the unedited GitHub template).
- No measured performance, and a protocol ceiling far below what a pilot with many devices needs.
- A custom BFT implementation whose most recent critical bug was found less than a day before this audit.
- Spring Boot 3.5 (out of free support since June 2026) and Java 17 (out of Oracle premier support).
- Features that exist in name more than in substance: governance unwired, the `LIGHT` role incomplete, AI unevaluated, differential privacy without accounting, ZK limited to one reviewed-but-unaudited proof.

## Positioning recommendation

Position it as **research infrastructure that is converging on a pilot platform for
multi-operator device-attested records** (see G). Stop adding features. Spend the next quarter on
consensus correctness under restarts and real networks, measured performance, and one narrow
end-to-end application compared against Fabric and against a "database + signatures +
transparency log" baseline.
