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
| HC-025 block production | `dc268a4` | `hc025` pass + `BlockProductionTest` |
| HC-020 genesis | `bcc8390` | `hc020` pass (cross-node apply) |
| HC-021 fork validation | `e415429` | `hc021` pass + restore-path test |
| HC-029 txid from wire | `87fc9ee` | `hc029` pass |
| HC-024 rejected-block side effects | `bb0c1d9` | `hc024`, `hc024b` pass |
| HC-027 stale mempool state | `24cbe24` | `hc027` pass |
| HC-030 Merkle | `81f3567` | `hc030` pass |
| HC-031 pruning/snapshots | `cd13e72` | `hc031` pass |
| HC-028 state root coverage | `46df88a` | `hc028` pass |
| HC-041 storage AAD | `f6b9f63` | `hc041` pass |
| HC-007 handshake allocation | `dd7188b` | `hc007` pass |
| HC-002 quorum | `4c01596` | `hc002` pass + `QuorumPropertyTest` |
| HC-001 gossip threshold | `7f57727` | `hc001` pass (fresh JVM, no override) |
| HC-005 + HC-003 CA | `02a9066` | `hc003`, `hc005` (2 tests) pass |
| HC-006 trust-all TLS | `a328bb3` | `hc006` pass + foreign-CA test |
| HC-004 IoT ingress signer | `4d892d3` | `hc004` pass |

**Status: all 30 audit tests pass; main suite 622/622.**

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

## Round 2

### HC-025 — The honest block producer never included a transfer
**Before.** `createBlock` checked candidates with mempool rules: the expected nonce was "one after
the highest pending nonce", and the candidate itself is pending, so every ACCOUNT transfer failed
with "expected n+1 got n" and was dropped. **What happened.** A valid pending transfer was never put
in a block. **After.** Candidates are checked with block rules: the nonce must follow the simulated
state (several transactions from one sender chain in one block) and the balance is enforced by
applying to the simulated state. **Verified.** `hc025`; `BlockProductionTest` goes through the real
path `addTransaction -> createBlock -> apply` (no test did before; they all used `BlockApplier`).

### HC-020 — Each node had its own genesis
**Before.** Genesis used the wall clock. **What happened.** Two nodes started 25 ms apart had
different genesis hashes and node B rejected node A's block 1. **After.** `Config.GENESIS_TIMESTAMP`
(a fixed constant, deliberately not an environment variable) and the protocol difficulty.
**Verified.** `hc020`: same genesis, and B applies A's block. Existing data directories keep their
old genesis: start test networks from empty storage.

### HC-021 — A junk block could undo the tip
**Before.** For a block at the tip's height, the node compared hash strings, reverted its tip, and
only then validated the newcomer; if validation failed the tip stayed reverted. **After.** The
candidate's hash, parent, validator and signature are checked first; if full application still
fails, the old tip is re-applied. **Verified.** `hc021`; `BlockProductionTest#invalidForkCandidateRestoresTip`.

### HC-029 — Peers could choose a transaction's id
**Before.** Jackson overwrote the computed `txid` with whatever the JSON said. **After.** `txid` is
output-only (`READ_ONLY`); the unused `setData` was removed. **Verified.** `hc029`.

### HC-024 — Rejected blocks left traces in storage
**Before.** Receipts, indexes, telemetry, the FL model update/broadcast and token registrations
were written while the block was still being checked. **What happened.** A rejected block left a
SUCCESS receipt and a registered token. **After.** All of these are buffered and written only after
the final state-root check; `TokenRegistry` stages changes per block (commit/rollback).
**Verified.** `hc024`, `hc024b`.

### HC-027 — The mempool used a stale state after a restart
**Before.** The mempool held the very first `AccountState` object. **What happened.** After a
restart, an account at nonce 101 could not submit nonce 102. **After.** The mempool asks the chain
for its current state each time. **Verified.** `hc027`.

### HC-030 — Two transaction lists, one block hash
**Before.** Odd Merkle levels duplicated their last node. **After.** The odd node is promoted, and
leaves and inner nodes are hashed with different tags. Consensus change. **Verified.** `hc030`.

### HC-031 — Pruning freed nothing; snapshots grew forever
**Before.** Pruning deleted a key that never existed and wrote an extra full snapshot, and every
block kept a full snapshot. **After.** `Storage.deleteBlock` uses the real key; only the last 16
snapshots are kept. **Verified.** `hc031`: the pruned block is gone, 2 snapshots instead of 7.

### HC-028 — The state root ignored token balances, commitments and ABIs
**After.** Each account's committed encoding includes its sorted non-zero token balances, ZK
commitment and ABI. Consensus change. **Verified.** `hc028`; the clone round-trip property holds.

### HC-041 — Encrypted records could be moved between keys
**After.** AES-GCM authenticates the storage key (AAD); a moved record fails with
`AEADBadTagException`. Data written by older versions is unreadable: recreate data directories.
Remaining limitation: key names are stored in plaintext. **Verified.** `hc041`.

### HC-007 — One packet made the node try a ~2 GB allocation
**After.** The handshake CHALLENGE must be exactly 97 bytes and is rejected before any allocation.
**Verified.** `hc007`: a clean `IOException`.

### HC-002 — Unsafe commit threshold for 5, 6, 8, 9 ... validators
**Before.** Quorum `2f+1`, safe only when n = 3f+1. **What happened.** With 5 validators, two honest
replicas certified different blocks. **After.** `quorumSize(n) = ceil((n+f+1)/2)` from the current
validator set, used for commits, view changes, checkpoints and fast sync. **Verified.** `hc002`;
`QuorumPropertyTest` checks intersection and liveness for n = 4..64 and that the old formula was
unsafe exactly when n != 3f+1.

### HC-001 — The minimum network never sent a message
**Before.** Gossip waited for 4 peers; a 4-validator network has 3 per node. Tests passed only
because of `-DMIN_PEERS=1`. **After.** Default 1 (overridable); the override was removed from
`pom.xml`, so the suite runs with the production default. **Verified.** `hc001` in a fresh JVM.

### HC-005 + HC-003 — Production mTLS could not connect, and the CA key was the disk key
**Before.** The CA paired a seed-derived private key with a random public key (nothing verified),
and the seed was `STORAGE_AES_KEY`. **After.** Public key = private key x G; the CA is seeded from
`NETWORK_CA_SEED` (at least 32 bytes, the same on every node, must differ from `STORAGE_AES_KEY`,
which is now a real per-node secret). **Verified.** `hc005`: CA-path handshake client=OK server=OK;
`hc003`: the CA key is not derivable from the disk key. `EDGE_DEPLOYMENT.md` and `MTLS_SETUP.md`
updated.

### HC-006 — The legacy TLS path trusted everyone
**After.** No trust-all by default; only an explicit test property enables it.
`MTLSSecureNetworkTest` now uses the real CA path and has a new negative test (a node whose
certificate comes from another CA is not admitted). **Verified.** `hc006`: attacker certificate
REJECTED.

### HC-004 — Every MQTT/CoAP reading was rejected
**After.** Ingress transactions are sent from the address of the key that signs them. Whether the
gateway may report for a given device is still HC-013. **Verified.** `hc004`: `verify()=true`.

## Audit-test corrections made during the fix pass
- `hc007` passed vacuously after `PROTOCOL_VERSION` became 2 (rejected with "Mismatch" before the
  allocation it tests). It now sends the current version and fails for the real reason
  (`OutOfMemoryError`) — HC-007 is **still open**.
- `hc041` passed vacuously (Integer vs Long comparison). It now compares numerically and fails —
  HC-041 is **still open**.
- Six audit tests errored in setup because they minted to arbitrary addresses (now forbidden by
  HC-016); they fund through the proposer and fail on their own assertion.

## Still open (no failing audit test: LIKELY findings and design items)
- **HC-008** per-node `REQUIRE_QUANTUM_SIG` changes transaction validity; **HC-009** `API_PORT`
  ignored; **HC-010** default key-generator seed; **HC-011** the handshake checks key possession,
  not validator membership; **HC-012** no socket timeouts, unbounded threads; **HC-013** device
  authorisation for gateway telemetry; **HC-014** proposer-loop NPE; **HC-015** the remaining test
  override (`-DREQUIRE_QUANTUM_SIG=false`).
- **HC-032** fast-sync atomicity; **HC-033** unchecked arithmetic and missing per-type fee checks;
  **HC-034** live chain/state exposed without locking; **HC-035** checkpoint `utxoRoot` hashes a
  `HashMap.toString()`.
- Not yet numbered: `MultiSigManager.signProposal(id, address, signature)` stores the signature
  unverified; on-chain slashing evidence (follow-up to HC-019); PBFT view change / NEW_VIEW design.
- Consensus changes in this pass (genesis, Merkle tree, state root, storage AAD, handshake v2): all
  nodes must upgrade together, and test networks restart from empty storage.
