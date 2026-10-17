# HybridChain — Fix log (audit batch 2)

Branch `fix/audit-batch2`. For each fix: **Before** (what the code did), **What happened**
(the observed consequence, from the executed audit test), **After** (the new behaviour), and
how it was verified. Main test suite after every fix: green (610 → 616 tests, the 6 new ones are
`UtxoValidationTest`). Audit tests: `mvn -o test -Dtest=HybridChainAuditPoCTest -Daudit.excluded=none`.

| Fix | Commit | Audit test now |
|---|---|---|
| HC-040 + HC-042 signing | `fa24583` | `hc040`, `hc042` pass |
| HC-016 block reward | `4edaa80` | `hc016`, `hc016b` pass |
| HC-017 failed-tx fees | `f59c647` | `hc017` pass |
| HC-018 UTXO | `bd174c9` | `hc018` pass + `UtxoValidationTest` (6) |
| HC-019 local slashing | `7aedad2` | `hc019` pass |
| HC-022 + HC-023 counters | `c8c5ba2` | `hc022`, `hc022b`, `hc023` pass |
| HC-026 telemetry determinism | `ca5ccfa` | `hc026` pass |

---

## HC-040 — Signatures covered only the first 32 bytes of a message (`Crypto.java`, `SSIManager.java`)

**Before.** `Crypto.sign(message, key)` handed `message` straight to ECDSA. ECDSA is defined
over a 256-bit number and silently uses only the leftmost 32 bytes of whatever it is given. Most
callers hashed first, but `SSIManager.transferOwnership` and `addDelegate` signed the plain string
`"transfer:<did>:<newOwner>"`. The DID is longer than 32 bytes on its own, so the part naming the
new owner was never covered. `P2PMessage`, the ECDSA half of the post-quantum `HybridSignature`,
and `HardwareSecurityModule.sign` had the same pattern.

**What happened.** The owner signed "transfer to alice"; the identical signature was accepted for
"transfer to mallory" and ownership moved.

**After.**
- `Crypto.sign` throws `IllegalArgumentException` unless the input is exactly 32 bytes;
  `Crypto.verify` returns `false`. Truncation can no longer happen silently anywhere.
- New `Crypto.hashWithDomain(domain, parts…)`: SHA-256 over a purpose tag, a separator, and each
  field with its length. The tag means a signature made for one purpose cannot verify for another;
  the lengths mean two different field lists cannot encode to the same bytes.
- Transfer/delegation sign `SSIManager.transferOwnershipDigest(did, newOwner)` /
  `delegationDigest(...)`; P2P, hybrid and HSM signatures cover a digest of the whole payload.

**Verified.** `hc040`: the alice signature is rejected for mallory, a raw 41-byte message never
verifies. Seven tests that had signed raw bytes themselves now hash first; `CryptoCompleteTest`
additionally asserts a raw message is rejected.

## HC-042 — The handshake would sign whatever 32 bytes a peer sent (`PeerNode.java`)

Found while fixing HC-040.

**Before.** During connection setup the node signed the peer's "nonce" directly with its
consensus key. Every protocol digest in the system — a transaction's signing payload, a block hash —
is also 32 bytes.

**What happened.** A connecting peer could choose its "nonce" and receive a valid signature by the
validator's key over that value.

**After.** The node signs `handshakeDigest(nonce, signerAddress, networkId)` — tagged
`HC-P2P-HANDSHAKE-v2` — which can never equal a transaction or block digest. `PROTOCOL_VERSION`
is now 2: old and new nodes cannot connect to each other (intended; mixed versions would fail the
handshake anyway).

**Verified.** `hc042`: a handshake signature does not verify against the raw nonce.

## HC-016 — Unlimited block rewards, and rewards for whoever submitted a MINT (`Blockchain.java`, `Mempool.java`)

**Before.** A MINT was checked in isolation ("amount equals the scheduled reward"). Nothing
limited a block to one MINT or said who it must pay. `addTransaction` accepted MINTs from the
network, the mempool treated every sender-less transaction as "ready", and the honest
`createBlock` included them next to its own reward.

**What happened.** A block with ten MINTs created 10× the reward for one address; a MINT gossiped
by an outsider was included by an honest proposer and paid that outsider 50.

**After.**
- `addTransaction` and the mempool reject every transaction without a sender (MINT, unsigned UTXO).
- Block validation: at most one MINT, and its recipient must be the block's `validatorId`.
- `createBlock` labels its block with the address it mints and pays fees to.

**Verified.** `hc016`: block rejected, attacker balance 0. `hc016b`: foreign MINT rejected; the
honest block contains exactly one MINT. Tests that funded accounts by minting to them now use
`LedgerPoCFixture.fund()` (proposer mints, then transfers); `MempoolCompleteTest` M1.11 asserted
the old behaviour and now asserts the rejection.

## HC-017 — Fees of transactions that failed were still paid out (`Blockchain.java`)

**Before.** Each transaction in a block is checked against the balance *before* the block, so two
spends from one sender can each look affordable. During application, a transaction that threw got
a FAILED receipt — and its fee, which was never debited, was still added to the proposer's reward.

**What happened.** Supply went from 50 to 75 with no MINT in the block.

**After.**
- A transaction that cannot be applied makes the whole block invalid. Honest producers never hit
  this: `createBlock` simulates every candidate and drops the ones that fail.
- New `ChargedFailureException` for the legitimate case "executed and failed after its fee and
  nonce were charged" (a contract out of gas): it keeps its FAILED receipt and its fee counts.
- The fee sum uses `Math.addExact`.

**Verified.** `hc017`: block rejected, supply stays 50. Six contract out-of-gas tests still see
FAILED receipts. The UTXO double-spend test now expects the block to be rejected; an end-to-end test
that committed an invalid FL transaction (32 zero bytes = no model hash) now uses a real hash. Two
unrelated timing flakes (a block reusing its parent's millisecond) were fixed with parent + 1.

## HC-018 — UTXO transactions were unsigned, unowned and could have negative outputs (`Blockchain.java`, `UTXOSet.java`)

**Before.** The signature check ran only when a transaction had a sender, and UTXO transactions
need none. Validation checked that inputs exist and `inputs ≥ outputs + fee`, never who owns an
input, whether an input repeats, or whether an output is positive.

**What happened.** An unsigned transaction with no inputs and outputs `[−10⁹, +10⁹]` passed
`0 ≥ 0`, and the attacker's balance became 1,000,000,000.

**After.** A UTXO spend must be signed; every input must exist, appear once and belong to the
signer (`UTXOSet.getOwner`); every output must be positive and addressed; sums use exact
arithmetic. `UTXOSet.addOutput` itself refuses non-positive amounts and overwriting an output.

**Verified.** `hc018` (mempool and block both reject), plus `UtxoValidationTest`: valid spend,
foreign input, duplicate input, negative/zero output, unsigned, set invariants.

## HC-019 — Slashing used evidence only this node had seen (`Blockchain.java`)

**Before.** When this node received two conflicting votes from a validator, the consensus engine
added it to a local "slashed" set; the next applied block debited that validator's balance. Other
nodes that did not see the conflicting votes did not debit it.

**What happened.** The node that saw the equivocation rejected the network's committed block 2
("Invalid state root") — it forked itself off.

**After.** The state transition no longer debits anyone from local evidence. Equivocation is still
detected and logged. Economic slashing has to return as an on-chain evidence transaction that every
node applies identically (planned feature).

**Verified.** `hc019`: the block is accepted on the node that saw the equivocation.

## HC-022 + HC-023 — Rollback and restart left chain counters wrong (`Blockchain.java`)

**Before.** On a fork, `revertTip` restored balances from the parent's snapshot but not
`totalMinted` or `baseFee`. `baseFee` is checked during block validation, so it is
consensus-critical. On restart, the normal path loaded the snapshot but never read `totalMinted`.

**What happened.** After a fork `totalMinted` was 150 while real supply was 100; the forked node's
`baseFee` was 3 while a node that never saw the fork had 2 — a transaction paying 2 was valid on
one node and invalid on the other. After a restart `totalMinted` was 0.

**After.** Every per-block snapshot also stores `_totalMinted` and `_baseFee`; `revertTip`
restores both together with balances; the restart path restores `totalMinted`.

**Verified.** `hc022` (100 = 100), `hc022b` (2 = 2), `hc023` (100 after restart).

## HC-026 — Telemetry results depended on each node's private detector memory (`Blockchain.java`)

**Before.** Applying a TELEMETRY transaction asked the anomaly detector — whose sliding window
lives in this JVM's memory, is empty after a restart, and is fed by other code paths — and charged
up to 5× the fee and cut reputation when it said "anomaly".

**What happened.** The same transaction on the same starting state left balance 999000 on a freshly
started node and 995000 on a node that had seen earlier readings — two different state roots.

**After.** The state transition charges the declared fee only. After a block is accepted,
`runAdvisoryAnomalyDetection` feeds each reading to the detector and publishes a metric/event; it
never changes state.

**Verified.** `hc026`: both nodes now produce state root `8889a7…`. Two tests that asserted the old
penalty now assert the anomaly is detected while state is unaffected.

---

## Audit-test corrections made during the fix pass
- `hc007` passed vacuously after `PROTOCOL_VERSION` became 2 (rejected with "Mismatch" before the
  allocation it tests). It now sends the current version and fails for the real reason
  (`OutOfMemoryError`) — HC-007 is **still open**.
- `hc041` passed vacuously (Integer vs Long comparison). It now compares numerically and fails —
  HC-041 is **still open**.
- Six audit tests errored in setup because they minted to arbitrary addresses (now forbidden by
  HC-016); they fund through the proposer and fail on their own assertion.

## Still open (18 audit tests fail on purpose)
HC-001, 002, 003, 004, 005 (×2), 006, 007 (batch 1); HC-020, 021, 024, 024b, 025, 027, 028, 029,
030, 031, 041 (batch 2). Also noted, not yet a numbered finding: `MultiSigManager.signProposal(id,
address, signature)` stores the signature without verifying it.
