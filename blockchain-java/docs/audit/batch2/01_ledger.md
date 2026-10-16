## PART 3b — Detailed findings, batch 2 · module 1: Ledger

Scope: every member of `Blockchain`, `PrunedBlockchain`, `AccountState` (+`Account`), `Transaction` (+`Builder`, `Type`), `Block`, `UTXOSet` (+inner types), `UTXOInput/Output`, `Mempool`, `FeeMarket`, `Tokenomics`, `TokenRegistry`, `TransactionReceipt`, `MerklePatriciaTrie` (+nodes), `MerkleTree`, `Checkpoint`, `Difficulty`, `ExecutionResult`, `Validator`, `Utils`, `BlockValidationException` — **412 ledger rows**, all reviewed (347 REVIEWED-CLEAN, 52 REVIEWED-FINDING, 13 DEAD-CODE, 0 NOT-VERIFIED). Row-level notes: `audit/AUDIT_LEDGER.md`, source of truth `audit/tools/review_ledger.py`.

**PoC harness.** `LedgerPoCFixture` builds a real `Blockchain` over real LevelDB `Storage` with a real 4-validator `PBFTConsensus` — no mocks, no spies. Its `build()` computes the post-state root exactly as `Blockchain.applyBlockInternal` does (same tx sort, per-tx exceptions swallowed, every fee credited to the proposer), i.e. the root an honest re-executing node computes; `commitAndApply()` records the commit certificate the way `PeerNode.applyBlockAtSequence:761` does and calls `applyBlock`. A block that this fixture gets accepted is therefore a block **every honest node accepts**. Output of every PoC: `audit/evidence/poc/poc_suite_output.txt`.

**Why honest validators vote for bad blocks.** The gossip BLOCK handler (`PeerNode.java:185-224`) runs only the structural `validateBlock` before sending PREPARE/COMMIT; nothing executes the state transition before voting. A block is executed only after it is committed. So every "a single proposer can …" below means: one Byzantine validator, when it is leader, gets the block finalised and then every honest node applies it identically (deterministic bugs) or diverges (nondeterministic bugs).

### Batch 2 · Ledger — findings table

| ID | Severity | Status | Module | File:Line | Title |
|---|---|---|---|---|---|
| HC-016 | CRITICAL | CONFIRMED | Ledger | `Blockchain.java:358-367`, `Mempool.java:212-225`, `Blockchain.java:1036-1050` | Unlimited MINTs per block; any peer's gossiped MINT is included by honest proposers |
| HC-017 | CRITICAL | CONFIRMED | Ledger | `Blockchain.java:779-781`, `:745-747` | Fees of transactions that FAIL during apply are still credited — money from nothing |
| HC-018 | CRITICAL | CONFIRMED | Ledger | `Blockchain.java:228,256-274,1114-1123`, `UTXOSet.java:44-47` | UTXO transactions are unsigned, unowned and accept negative outputs |
| HC-019 | CRITICAL | CONFIRMED | Ledger×Consensus | `Blockchain.java:806-822`, `PBFTConsensus.java:563-568,598-603` | Node-local equivocation evidence changes the state transition → honest nodes reject committed blocks |
| HC-022 | CRITICAL | CONFIRMED | Ledger | `Blockchain.java:653-685`, `FeeMarket.java:68-90` | Fork revert leaves `totalMinted` and the consensus-critical `baseFee` advanced → split |
| HC-026 | CRITICAL | CONFIRMED | Ledger×AI | `Blockchain.java:1213-1220` | TELEMETRY state transition reads the process-wide anomaly detector → same tx, different post-state |
| HC-020 | HIGH | CONFIRMED | Ledger | `Blockchain.java:191` | Genesis hash depends on the wall clock at first start → independently started nodes never agree |
| HC-021 | HIGH | CONFIRMED | Ledger | `Blockchain.java:611-651` | A same-height block reverts the tip **before** it is validated |
| HC-024 | HIGH | CONFIRMED | Ledger | `Blockchain.java:783-796,1258-1260,1360-1383`, `TokenRegistry.java` | A rejected block leaves SUCCESS receipts, tx-index entries and token registrations behind |
| HC-025 | HIGH | CONFIRMED | Ledger | `Blockchain.java:296-305,1071-1073` | Honest block producer never includes a pending ACCOUNT transfer |
| HC-028 | HIGH | CONFIRMED | Ledger | `AccountState.java:578-616,454-475` | State root omits token balances, ZK commitments and ABI |
| HC-029 | HIGH | CONFIRMED | Ledger×P2P | `Transaction.java:48,62-85`, `Blockchain.java:1575` | Transaction id is taken from the wire, not from the content |
| HC-023 | MEDIUM | CONFIRMED | Ledger | `Blockchain.java:80-105` | Restart via the snapshot path loses `totalMinted` and all in-memory history |
| HC-027 | MEDIUM | CONFIRMED | Ledger | `Blockchain.java:57`, `Mempool.java:41,298-307` | Mempool judges nonce gaps against a stale `AccountState` |
| HC-030 | MEDIUM | CONFIRMED | Ledger | `MerkleTree.java:10-24` | Merkle tree duplicates the odd leaf (CVE-2012-2459 pattern): two tx lists, one block hash |
| HC-031 | MEDIUM | CONFIRMED | Ledger×Storage | `PrunedBlockchain.java:30-45`, `Storage.java:554-557`, `Blockchain.java:861` | Pruning deletes nothing; per-block full-state snapshots are never pruned |
| HC-034 | MEDIUM | LIKELY | Ledger×API | `Blockchain.java:32,216-217,1534,1552`, `AccountState.java:58` | Live chain/state exposed to REST threads without locking; non-volatile flags |
| HC-032 | LOW | LIKELY | Ledger | `Blockchain.java:127-188` | Fast-sync path: `2f+1` quorum and non-atomic fallback (unreachable in current wiring) |
| HC-033 | LOW | LIKELY | Ledger | see detail | Unchecked arithmetic and missing per-type checks (baseFee, fee balance), MPT API traps |
| HC-035 | LOW | LIKELY | Ledger | `Blockchain.java:891` | Checkpoint `utxoRoot` hashes `HashMap.toString()` → iteration-order dependent |

### HC-016 — Unlimited MINTs per block; foreign MINTs are included by honest proposers
**Severity:** CRITICAL — likelihood HIGH (no signing key needed: any connected peer) × impact HIGH (unbounded inflation / supply cap bypass) · **Status:** CONFIRMED · **Consensus-rule change:** YES

**Description.** `validateTransaction` checks a MINT in isolation: `from == null` and `amount == Tokenomics.getCurrentReward(height+1, totalMinted)` (`Blockchain.java:358-367`). Nothing limits a block to one MINT, binds the MINT to the proposer, or accounts for other MINTs in the same block. Separately, `addTransaction` accepts `from == null` transactions from any source, `Mempool.getReadyTransactions` treats every `from == null` transaction as "immediately ready" (`Mempool.java:212-225`), and `createBlock` validates and includes them next to its own reward (`Blockchain.java:1036-1077`). The gossip TX handler passes every peer-supplied transaction straight to `addTransaction` (`PeerNode.java:174-183`). (REST: `IoTRestAPI.java:270-276` skips token verification when `from == null`, but then calls `getNonce(null)`, which throws on the `ConcurrentHashMap`-backed state of a never-restarted node; REST reachability is therefore NOT-VERIFIED here and is re-examined in the API module.)

**Scenario.** A2 (any peer that completes the handshake — today any key holder, HC-011) gossips `{"type":"MINT","to":"<me>","amount":<reward>}`. The next honest leader includes it; every node applies it. Repeat every block. A1 (Byzantine leader) can put any number of MINTs in its own block.

**PoC (executed).**
```
[HC-016] reward=50 accepted=true attackerBalance=500 totalMinted=500
[HC-016b] foreign MINT ACCEPTED into mempool; honest block has 2 MINT txs; apply=ok; attacker balance=50
```
`hc016_multipleMintsPerBlock`, `hc016b_honestProposerIncludesForeignMint`.

**Fix.** MINT becomes a block-level rule, never a mempool transaction.
```java
// Blockchain.addTransaction — first statement inside the try:
if (tx.getFrom() == null)
    throw new Exception("System transactions (from == null) are created by the block producer only");

// Mempool.addInternal — after the null/id check:
if (tx.getFrom() == null)
    throw new IllegalArgumentException("system transactions are not accepted into the mempool");

// Blockchain.applyBlockInternal — before the per-tx validation loop:
long mintCount = 0;
for (Transaction tx : block.getTransactions()) {
    if (tx.getType() == Transaction.Type.MINT) {
        mintCount++;
        if (!block.getValidatorId().equals(tx.getTo()))
            throw new Exception("MINT must pay the block proposer");
    }
}
if (mintCount > 1)
    throw new Exception("At most one MINT per block, got " + mintCount);
```
**Regression test:** `hc016*` turn green; add a gossip-handler test that a peer MINT is dropped, and (API module) a MockMvc test that a `type=MINT` submission returns 4xx.
**Interaction warnings.** Rejecting `from == null` in the mempool also closes the HC-018 ingress path but not the Byzantine-proposer path — HC-018's own fix is still required. `createBlock` already credits its own MINT to `minerAddress`; App passes `NODE_ID` (`App.java:130,233`), which must equal the validator id used in `block.setValidatorId` for the new `getTo()` check to pass.

### HC-017 — Fees of failed transactions are credited to the proposer
**Severity:** CRITICAL — likelihood MEDIUM (needs one Byzantine leader) × impact HIGH (money creation, every block it leads) · **Status:** CONFIRMED · **Consensus-rule change:** YES

**Description.** Block validation checks each transaction against the **pre-block** state (`validateTransaction(tx, true)`, `Blockchain.java:745-747`), so two transactions from the same sender can each look affordable. During apply, an exception marks the receipt `FAILED` (`:770-775`) but `totalFees += tx.getFee()` runs unconditionally (`:779`) and the proposer is credited the full sum (`:798-800`), although the failed transaction's fee was never debited.

**Scenario.** A1: leader includes `tx1 = (amount r/2, fee r/2)` and `tx2 = (amount r/2, fee r/2)` from a sender holding `r`. tx2 fails; proposer still receives `r` in fees. Supply grows by `r/2`. Scales with block size.

**PoC (executed).** `[HC-017] supply before=50 after=75 (created from nothing: 25); proposer balance=50` (`hc017_failedTxFeeCreditedToProposer`).

**Fix.** Credit only what was debited, and make each transaction atomic.
```java
// applyBlockInternal, replace the per-tx body (inside "for (Transaction tx : sortedTxs)"):
AccountState txState = state.cloneState();
UTXOSet txUtxo = UTXOSet.fromMap(utxo.toJSON());
long feeCharged = 0;
try {
    result = applyTransactionToState(txState, txUtxo, tx, block.getIndex(), block.getTimestamp(), block.getHash(), events);
    gasUsed = result.getGasUsed();
    state.merge(txState);                 // commit this tx only on success
    utxo = txUtxo;
    feeCharged = tx.getFee();
} catch (RevertException re) {
    receiptStatus = TransactionReceipt.STATUS_REVERTED;
    receiptError = re.getMessage();
    result = new ExecutionResult(0, null, re.getReturnData(), events);
    if (tx.getFrom() != null && state.getBalance(tx.getFrom()) >= tx.getFee()) {
        state.debit(tx.getFrom(), tx.getFee());   // reverted calls still pay
        state.incrementNonce(tx.getFrom());
        feeCharged = tx.getFee();
    }
} catch (Exception ex) {
    throw new Exception("Block contains an inapplicable transaction " + tx.getId() + ": " + ex.getMessage(), ex);
}
totalFees = Math.addExact(totalFees, feeCharged);
```
(`state.merge` on a per-tx clone is O(state); for production replace with a journaled `AccountState` — see Part 9.) Validation must become cumulative: run `validateTransaction` against the evolving `txState`, not the pre-block state.
**Regression test:** `hc017` green; property test "Σ balances + Σ UTXO == totalMinted after every block" (`LedgerPropertyTest` extension).
**Interaction warnings.** Rejecting blocks that contain a failing tx (instead of FAILED receipts) changes liveness: `createBlock` must simulate cumulatively so honest leaders never produce such blocks (today HC-025 hides this because honest leaders include almost nothing).

### HC-018 — UTXO transactions are unsigned, unowned and accept negative outputs
**Severity:** CRITICAL — likelihood HIGH (any connected peer; no key) × impact HIGH (unbounded money; theft of every UTXO) · **Status:** CONFIRMED · **Consensus-rule change:** YES

**Description.** The signature check is skipped when `from == null` (`Blockchain.java:228`), and a UTXO transaction needs no `from` (`:244`). The UTXO branch checks only that inputs exist and `Σin ≥ Σout + fee` (`:256-274`): input ownership is never checked, output amounts are never checked for sign, and `UTXOSet.addOutput` stores negative amounts (`UTXOSet.java:44-47`). `getBalance` adds UTXO balances to account balances (`Blockchain.java:1002-1012`).

**Scenario.** A2: submit `outputs = [(victim, −10⁹), (attacker, +10⁹)]`, no inputs, no signature: `0 ≥ 0`. Or spend any victim's UTXO into your own address. Ingress: gossip TX handler (`PeerNode.java:174-183`) → `addTransaction` (verified in the PoC); REST is re-examined in the API module (see HC-016 note on `getNonce(null)`).

**PoC (executed).** `[HC-018] mempoolAccepted=true blockAccepted=true attacker getBalance=1000000000 utxoTotal=0` (`hc018_unsignedUtxoNegativeOutputPrintsMoney`).

**Fix.**
```java
// Blockchain.validateTransaction, replace "case UTXO:" body:
case UTXO: {
    if (tx.getFrom() == null || !tx.verify())
        throw new Exception("UTXO transaction must be signed by the owner of its inputs");
    if (tx.getInputs().isEmpty() || tx.getOutputs().isEmpty())
        throw new Exception("UTXO transaction missing inputs/outputs");
    java.util.Set<String> seen = new java.util.HashSet<>();
    long totalInput = 0;
    for (UTXOInput inp : tx.getInputs()) {
        String key = inp.getTxid() + ":" + inp.getIndex();
        if (!seen.add(key)) throw new Exception("Duplicate UTXO input " + key);
        if (!utxo.isUnspent(inp.getTxid(), inp.getIndex())) throw new Exception("UTXO input not available");
        if (!tx.getFrom().equals(utxo.getOwner(inp.getTxid(), inp.getIndex())))
            throw new Exception("UTXO input " + key + " not owned by signer");
        totalInput = Math.addExact(totalInput, utxo.getAmount(inp.getTxid(), inp.getIndex()));
    }
    long totalOutput = 0;
    for (UTXOOutput out : tx.getOutputs()) {
        if (out.getAmount() <= 0 || out.getAddress() == null || out.getAddress().isBlank())
            throw new Exception("UTXO outputs must be positive and addressed");
        totalOutput = Math.addExact(totalOutput, out.getAmount());
    }
    if (totalInput < Math.addExact(totalOutput, tx.getFee()))
        throw new Exception("Insufficient UTXO input sum");
    break;
}

// UTXOSet — new accessor, and reject non-positive outputs at the data-structure level:
public String getOwner(String txid, int index) {
    UTXO u = map.get(txid + ":" + index);
    return u == null ? null : u.getAddress();
}
public void addOutput(String txid, int index, String address, long amount) {
    if (amount <= 0) throw new IllegalArgumentException("UTXO amount must be positive");
    String key = txid + ":" + index;
    if (map.containsKey(key)) throw new IllegalStateException("UTXO already exists: " + key);
    map.put(key, new UTXO(address, amount));
}
```
In `applyTransactionToState` `case UTXO:` add `targetState.incrementNonce(tx.getFrom())` (UTXO txs must be replay-protected by the signer's nonce; today the nonce loop skips UTXO txs at `Blockchain.java:734`).
**Regression test:** `hc018` green; negative tests: unsigned UTXO, foreign input, duplicate input, negative output, zero output.
**Interaction warnings.** No UTXO is ever created except by UTXO transactions; once outputs must be positive and inputs owned, the UTXO model is unreachable until something seeds UTXOs. Decide: delete the UTXO type (recommended, Part 5) or add a signed ACCOUNT→UTXO conversion.

### HC-019 — Node-local equivocation evidence changes the state transition
**Severity:** CRITICAL — likelihood MEDIUM (one Byzantine validator) × impact HIGH (honest nodes reject committed blocks: fork/halt) · **Status:** CONFIRMED · **Consensus-rule change:** YES

**Description.** `PBFTConsensus.addPrepareVote/addCommitVote` add a validator to `slashedValidators` when **this node** sees two conflicting votes (`PBFTConsensus.java:563-568,598-603`). `applyBlockInternal` then debits `balance × min(1, 0.1·3^k)` from every locally-slashed validator (`Blockchain.java:806-822`) — state that is not in the block. A node that observed the equivocation computes a different state root from every node that did not.

**Scenario.** A1: Byzantine v2 sends a second, conflicting PREPARE only to honest h1. h1 rejects the next committed block ("Invalid state root") while h2..h4 accept it; h1 is forked off. Repeat per honest node.

**PoC (executed).** `[HC-019] locally slashed set = [hb81d2…] … apply of the network's committed block 2: REJECTED: Invalid state root: expected d9fb2b… got 5fd622…` (`hc019_localSlashingDivergesStateRoot`).

**Fix.** Slashing must be driven by evidence carried **in the block**.
```java
// New transaction type SLASH_EVIDENCE: data = two signed PBFTMessages from the same validator,
// same (view, seq, phase), different blockHash. Validation:
case SLASH_EVIDENCE: {
    PBFTConsensus.EquivocationEvidence ev = MAPPER.readValue(tx.getData(), PBFTConsensus.EquivocationEvidence.class);
    if (!((PBFTConsensus) consensus).verifyEquivocation(ev))
        throw new Exception("Invalid equivocation evidence");
    if (state.isEvidenceUsed(ev.id())) throw new Exception("Evidence already applied");
    break;
}
// applyBlockInternal: delete the "for (String slashedId : consensus.getSlashedValidators())" loop;
// apply penalties only from SLASH_EVIDENCE transactions, with integer arithmetic:
long penalty = Math.multiplyExact(state.getBalance(ev.validatorId()), 3L) / 10L;  // 30%, deterministic
```
`PBFTConsensus` keeps detecting equivocation locally but only *proposes* a SLASH_EVIDENCE tx; `getSlashedValidators()`/`clearSlashedValidator()` are no longer read by the ledger.
**Regression test:** `hc019` green; new test: two nodes, only one sees the equivocation, both apply the same evidence block and agree.
**Interaction warnings.** `clearSlashedValidator` currently runs before the state-root check, so a rejected block also forgets the evidence — the fix removes that path entirely. `Math.pow` (double) leaves the consensus path.

### HC-022 — Fork revert leaves `totalMinted` and `baseFee` advanced
**Severity:** CRITICAL — likelihood LOW-MEDIUM (needs a same-height fork: HC-002, HC-021, or two leaders after a view change) × impact HIGH (consensus split) · **Status:** CONFIRMED · **Consensus-rule change:** NO (bug fix)

**Description.** `revertTip` restores `state`/`utxo` from `snapshot:<h-1>` (`Blockchain.java:653-685`) but not `totalMinted`, the fee-market `baseFee` meta (`FeeMarket.java:68-90`), receipts, tx index or `TokenRegistry`. `baseFee` is consensus-critical: block validation runs `validateTransaction(tx, true)` which rejects `fee < baseFee` (`:296-298`).

**PoC (executed).**
```
[HC-022]  … totalMinted=150 stateSupply=100 nextReward=50
[HC-022b] baseFee parent=1 forkedNode=3 nonForkedNode=2
```
A transaction paying fee 2 is valid on the non-forked node and invalid on the forked one. **Correction:** in the first run both blocks were below the fee-market target and `baseFee` was pinned at its floor (1 → 1), so no divergence appeared; `hc022b` uses blocks above `TARGET_GAS_PER_BLOCK`, where it does.

**Fix.** Put both values in the snapshot and restore them atomically.
```java
// applyBlockInternal, replace the saveSnapshot call:
Map<String, Object> st = state.toJSON();
st.put("totalMinted", totalMinted);
st.put("baseFee", nextBaseFee);
storage.saveSnapshot((int) block.getIndex(), st, utxo.toJSON());

// revertTip, after loading the snapshot and before the write lock:
Map<String, Object> st = (Map<String, Object>) snapshot.get("state");
long restoredMinted = Utils.safeLong(st.get("totalMinted"));
long restoredBaseFee = Utils.safeLong(st.get("baseFee"));
// inside the write lock, next to the state/utxo swap:
this.totalMinted = restoredMinted;
storage.putMeta("totalMinted", restoredMinted);
feeMarket.saveBaseFee(storage, restoredBaseFee);
```
Long-term: `totalMinted` and `baseFee` belong in the state root (Part 5).
**Regression test:** `hc022`, `hc022b` green.
**Interaction warnings.** Fix HC-021 first — today the revert can be triggered by an invalid block, and making revert "more complete" does not make it safe.

### HC-026 — TELEMETRY apply reads the process-wide anomaly detector
**Severity:** CRITICAL — likelihood HIGH once telemetry flows (HC-004 currently blocks MQTT/CoAP ingress; REST telemetry works) × impact HIGH (state divergence in normal operation) · **Status:** CONFIRMED · **Consensus-rule change:** YES

**Description.** `applyTransactionToState` `case TELEMETRY` calls `TelemetryAnomalyDetector.getInstance().check(from, payload, ts)` and debits `fee × (multiplier−1)` (`Blockchain.java:1213-1252`). The detector's sliding window lives in JVM memory: it is empty after a restart, fed twice on the proposer (simulation in `createBlock` + apply), fed by `Blockchain.check()` and the AI endpoints, and never part of the state. Identical tx + identical pre-state → different post-state on different nodes.

**PoC (executed).**
```
[HC-026] node fresh: device balance after the SAME tx = 999000, stateRoot=8889a7…
[HC-026] node warm : device balance after the SAME tx = 995000, stateRoot=9abf91…
```
**Fix.** Remove the detector from the state transition; charge a deterministic fee. Anomaly scoring stays advisory (events/metrics) or moves to an on-chain, deterministic, integer-only model whose window is part of the state.
```java
case TELEMETRY: {
    targetState.debit(tx.getFrom(), tx.getFee());
    targetState.incrementNonce(tx.getFrom());
    byte[] payload = extractTelemetryPayload(tx.getData());          // pure function of tx data
    byte[] toStore = payload.length >= 1024 ? Crypto.hash(payload) : payload;
    String deviceId = resolveDeviceId(targetState, tx);               // pure function of state + tx
    if (targetState == this.state) pendingTelemetry.add(new Object[]{deviceId, (int) blockIndex, tx.getId(), toStore});
    targetState.getLifecycleManager().recordDeviceActivity(deviceId, true);
    return new ExecutionResult(0, null, null, transactionEvents);
}
// after the state-root check succeeds:
for (Object[] t : pendingTelemetry) storage.saveTelemetry((String) t[0], (int) t[1], (String) t[2], (byte[]) t[3]);
pendingTelemetry.clear();
// anomaly detection, off the consensus path:
if (eventBus != null) eventBus.publish("telemetry", tx);   // detector subscribes and only emits alerts
```
**Regression test:** `hc026` green.
**Interaction warnings.** The firmware-integrity penalty (`debit 5000`, `:1240`) is already deterministic but swallows exceptions; keep it, but outside a blanket `catch (Exception ignored)`. `TELEMETRY_BATCH` also feeds the detector but only logs, so it does not diverge — it still mutates node-local state and should use the same event path.

### HC-020 — Genesis depends on the wall clock
**Severity:** HIGH — likelihood CERTAIN for independently started nodes × impact HIGH (network never forms; no safety impact) · **Status:** CONFIRMED · **Consensus-rule change:** YES (genesis definition)

**Description.** `init()` creates `new Block(0, System.currentTimeMillis(), …)` (`Blockchain.java:191`); the block hash commits to the timestamp. Every node started from empty storage has its own genesis; `validateBlock` then rejects every peer block ("prevHash does not chain to tip"). Existing multi-node tests pass only because both chains are created in the same millisecond or share storage (`ForkResolutionTest.java:50-52` documents the collision workaround).

**PoC (executed).** `[HC-020] genesis A=67959c… B=ca33fd…; B applying A's block 1: Block prevHash does not chain to tip`.

**Fix.**
```java
// Config:
public static final long GENESIS_TIMESTAMP = Long.parseLong(
        System.getenv().getOrDefault("GENESIS_TIMESTAMP", "1767225600000")); // 2026-01-01T00:00:00Z
// Blockchain.init:
Block genesis = new Block(0, Config.GENESIS_TIMESTAMP, new ArrayList<>(),
        "0".repeat(64), Config.INITIAL_DIFFICULTY, state.calculateStateRoot());
```
plus a `GENESIS_HASH` pin checked at startup and in the P2P HELLO, so mis-configured nodes fail fast.
**Regression test:** `hc020` green.
**Interaction warnings.** A per-node env var for genesis repeats the HC-008 pattern; ship it in a signed genesis file instead (Part 5).

### HC-021 — A same-height block reverts the tip before it is validated
**Severity:** HIGH — likelihood MEDIUM (reachable through a race between `validateBlock` and `applyBlock` on the gossip/sync paths, and through `applyBlockAtSequence` for an already-applied seq) × impact HIGH (finalised block undone; persistent `chain:tip` rewrite) · **Status:** CONFIRMED (API level) · **Consensus-rule change:** NO

**Description.** `applyBlock` routes `index == tip` to `handleFork` (`Blockchain.java:617-618`), which compares the attacker-supplied `block.getHash()` string with the tip hash and, if larger, calls `revertTip()` and only then `applyBlockInternal` → `validateBlock`. If validation fails, the revert stays. With PBFT, finalised blocks must never be reverted at all.

**PoC (executed).** `[HC-021] before: height=2 hash=004fbd… | after junk: height=1 hash=f04a7b… | error=Block hash mismatch` (junk block: hash `"zzzz…"`, validator `not-a-validator`, no signature).

**Fix.** Under PBFT, a committed height is final; remove fork choice. For PoA keep it but validate first, against the parent.
```java
public void applyBlock(Block block) throws Exception {
    lock.writeLock().lock();
    try {
        Block latest = getLatestBlock();
        if (block.getIndex() == latest.getIndex() + 1) {
            applyBlockInternal(block, latest);
        } else if (block.getIndex() == latest.getIndex() && !(consensus instanceof PBFTConsensus)) {
            handleFork(block);
        } else if (!block.getHash().equals(latest.getHash())) {
            log.warn("[BLOCKCHAIN] Ignored block {} at height {} (tip {})", block.getHash(), block.getIndex(), latest.getIndex());
        }
    } finally {
        lock.writeLock().unlock();
    }
}

private void handleFork(Block block) throws Exception {
    Block tip = getLatestBlock();
    if (!block.getHash().equals(block.calculateHash())) throw new BlockValidationException("Block hash mismatch", block.getHash(), block.getIndex());
    if (block.getHash().equals(tip.getHash()) || block.getHash().compareTo(tip.getHash()) <= 0) return;
    // validate the competitor against the PARENT state before touching the tip
    Blockchain probe = forkProbe();                       // read-only view rooted at tip-1 (snapshot)
    probe.applyBlockInternal(block, probe.getLatestBlock()); // throws if invalid; no side effects on this
    List<Transaction> evicted = new ArrayList<>(tip.getTransactions());
    revertTip();
    applyBlockInternal(block, getLatestBlock());
    for (Transaction t : evicted) if (t.getFrom() != null) { try { mempool.add(t); } catch (Exception ignored) { } }
}
```
(`forkProbe()` builds an in-memory `Blockchain` over the `snapshot:<tip-1>` state with a no-op `Storage`; until that exists, simply refuse forks — return early.)
**Regression test:** `hc021` green; `ForkResolutionTest` must be updated to sign competitor blocks properly.
**Interaction warnings.** HC-022 must be fixed together or the first legitimate fork splits the network.

### HC-024 — A rejected block leaves persistent side effects
**Severity:** HIGH — likelihood MEDIUM (one Byzantine leader, or any block that fails the root check) × impact MEDIUM-HIGH (exchanges/APIs see SUCCESS receipts for transactions that never executed; token-id squatting; telemetry written; FL model applied & broadcast) · **Status:** CONFIRMED · **Consensus-rule change:** NO

**Description.** Inside the per-tx loop, before the state-root check, `applyBlockInternal` writes receipts and tx/address indexes (`Blockchain.java:783-796`); `applyTransactionToState` writes telemetry (`:1258-1260`), applies and broadcasts FL models (`:1360-1368`), and calls `TokenRegistry` whose `persistToken` writes storage (`TokenRegistry.java`). The S1-01 rollback restores only `state`, `utxo`, `totalMinted`.

**PoC (executed).**
```
[HC-024]  block rejected=true (Invalid state root …); receipt=true status=SUCCESS blockHash=06439f…; tip height=1
[HC-024b] block rejected=true; tip height=1; token USDX registered=true owner=hb42958e…
```
**Fix.** Buffer every storage side effect and flush it only after the state-root check.
```java
// new field
private final List<Runnable> deferredWrites = new ArrayList<>();
// replace each direct write inside the apply path, e.g.
deferredWrites.add(() -> storage.saveReceipt(tx.getId(), receipt));
deferredWrites.add(() -> storage.indexTransaction(tx.getId(), block.getHash(), (int) block.getIndex()));
// TokenRegistry: pass a TokenRegistry.Txn that stages changes; commit after the root check.
// after "chain.add(block);":
for (Runnable w : deferredWrites) w.run();
deferredWrites.clear();
// in the catch block of applyBlockInternal:
deferredWrites.clear();
```
Better: one LevelDB `WriteBatch` per block (`Storage.beginBatch()/commitBatch()`), which also makes the tip/state/snapshot writes crash-atomic.
**Regression test:** `hc024`, `hc024b` green.
**Interaction warnings.** `TokenRegistry` state must move into `AccountState` (and the root, HC-028), otherwise revertTip has the same problem.

### HC-025 — Honest block producer never includes a pending ACCOUNT transfer
**Severity:** HIGH — likelihood CERTAIN × impact HIGH (value transfers never confirm through block production; only blocks built outside `createBlock` contain them) · **Status:** CONFIRMED · **Consensus-rule change:** NO

**Description.** `createBlock` validates each candidate with `validateTransaction(tx)` = `skipNonce=false` (`Blockchain.java:1071`). For ACCOUNT txs the expected nonce is `max(stateNonce+1, max(pending nonces)+1)` over the mempool (`:296-305`) — which includes the candidate itself, so the expectation is always `tx.nonce+1`. Every pending ACCOUNT tx is dropped with a debug log. The 604 green tests miss this because block-building tests use `BlockApplier`, which bypasses `createBlock`.

**PoC (executed).** `[HC-025] mempool=1 blockTxs=1 includedTransfer=false; validateTransaction(pending tx) -> Invalid nonce: expected 2 got 1`.

**Fix.** Block production must validate with block semantics (cumulative state, explicit nonce), not mempool semantics.
```java
// createBlock: replace validateTransaction(tx);
validateTransactionAgainst(clonedState, clonedUtxo, tx);   // same checks, reading clonedState
// and in validateTransaction(tx, skipNonce=false) exclude the tx itself from the pending scan:
for (Transaction ptx : mempool.toArray()) {
    if (tx.getFrom().equals(ptx.getFrom()) && !ptx.getId().equals(tx.getId())) {
        expectedNonce = Math.max(expectedNonce, ptx.getNonce() + 1);
    }
}
```
**Regression test:** `hc025` green; an end-to-end test that submits through `addTransaction` and builds with `createBlock` (no `BlockApplier`).
**Interaction warnings.** Fixing HC-025 activates HC-017 for *honest* proposers only if simulation and apply disagree; cumulative validation (HC-017 fix) closes it.

### HC-028 — State root omits token balances, commitments and ABI
**Severity:** HIGH — likelihood MEDIUM × impact HIGH (token balances are not consensus-checked; snapshot/checkpoint/fast-sync and proofs cannot detect token-state corruption or divergence) · **Status:** CONFIRMED · **Consensus-rule change:** YES

**Description.** `serializeAccount` encodes native balance, nonce, reputation, storage root, code, capabilities (`AccountState.java:578-616`); it ignores `tokenBalances` except `native`, `commitment` (used by PRIVATE_TRANSFER validation) and `abi`. `TokenRegistry` metadata and `totalMinted` live in storage only.

**PoC (executed).** Three states — base, +10⁹ USDX, +commitment — all produce root `12f032…`.

**Fix.**
```java
// serializeAccount, after the native balance:
java.util.TreeMap<String, Long> tokens = new java.util.TreeMap<>(acc.getTokenBalances());
tokens.remove("native");
dos.writeInt(tokens.size());
for (Map.Entry<String, Long> e : tokens.entrySet()) {
    byte[] k = e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    dos.writeInt(k.length); dos.write(k); dos.writeLong(e.getValue());
}
byte[] cm = acc.getCommitment();
dos.writeInt(cm == null ? 0 : cm.length); if (cm != null) dos.write(cm);
byte[] abi = acc.getAbi();
dos.writeInt(abi == null ? 0 : abi.length); if (abi != null) dos.write(abi);
```
and every mutator that can change these (`setTokenBalance`, `creditToken`, `debitToken`, `setCommitment`, `setAbi`) already calls `updateMpt`. Token registry entries become accounts under a reserved prefix (`token:<id>`) so they are rooted too.
**Regression test:** `hc028` green.
**Interaction warnings.** Changing the account encoding changes every state root: requires a coordinated upgrade (hard fork) and re-genesis for test networks.

### HC-029 — Transaction id is taken from the wire
**Severity:** HIGH — likelihood HIGH (any connected peer) × impact MEDIUM-HIGH (receipt overwrite, censorship by id pre-emption, mempool corruption) · **Status:** CONFIRMED · **Consensus-rule change:** NO

**Description.** The `@JsonCreator` constructor computes `txid` from content (`Transaction.java:85`), but `txid` is a non-final private field with a public getter, so Jackson's default `INFER_PROPERTY_MUTATORS` sets it from the JSON `"txid"` afterwards. `verify()` does not cover `txid`. Mempool keys, receipts, tx index and `mempool.remove(appliedId)` all use `getId()`.

**PoC (executed).** `[HC-029] content hash=0604ac…, getId() after deserialize=0000…0000, verify()=true`.

**Scenario.** A2 sees a victim's tx in gossip and races a different tx claiming the victim's id to other nodes: the victim's tx is rejected as "tx already in mempool". Or a Byzantine proposer includes a cheap tx carrying the id of a high-value deposit; its receipt overwrites the deposit's receipt.

**Fix.**
```java
@JsonIgnore
private final String txid;           // final + ignored: never deserialised
@JsonProperty(value = "txid", access = JsonProperty.Access.READ_ONLY)
public String getTxid() { return txid; }
```
(`txid` becomes final; the `sign(...)` instance methods that reassign it are replaced by returning a new signed `Transaction`.) Also make `setData` package-private or delete it — it mutates signed content after the id is computed.
**Regression test:** `hc029` green; same check for `Block` (`hash` field — currently re-verified in `validateBlock`, keep that).
**Interaction warnings.** None at the consensus layer (ids are not in block hashes; tx Merkle leaves hash the canonical encoding).

### HC-023 — Restart loses `totalMinted` and history
**Severity:** MEDIUM — likelihood CERTAIN on restart × impact MEDIUM (wrong supply metrics now; supply-cap divergence near the cap; fork resolution impossible after restart) · **Status:** CONFIRMED · **Consensus-rule change:** NO

**Description.** `saveSnapshot` writes `lastSnapshotHeight` every block (`Storage.java:268-276`), so `init()` always takes the snapshot path (`Blockchain.java:80-105`), which restores state/utxo/difficulty and returns — never reading `totalMinted`, and loading only the tip into `chain`. The next block then overwrites the persisted `totalMinted` meta with the wrong value (`:856`).

**PoC (executed).** `[HC-023] before restart: height=2 totalMinted=100 | after restart: height=2 totalMinted=0 chainInMemory=1 blocks`.

**Fix.** In the snapshot branch, before `return`: `Object m = storage.getMeta("totalMinted"); if (m instanceof Number) totalMinted = ((Number) m).longValue();` — and better, read it from the snapshot (HC-022 fix). Load the last `k` blocks (k ≥ finality depth 7) into `chain`.
**Regression test:** `hc023` green.

### HC-027 — Mempool judges nonce gaps against a stale state
**Severity:** MEDIUM — likelihood CERTAIN after restart/rollback × impact MEDIUM (accounts past nonce 100 cannot submit through that node) · **Status:** CONFIRMED

**Description.** The constructor calls `mempool.setStateRef(this.state)` once (`Blockchain.java:57`). `init()`, the S1-01 rollback and `revertTip` replace `this.state`; the mempool keeps the empty initial object (`Mempool.java:298-307`).

**PoC (executed).** `[HC-027] live nonce=101, submitting nonce 102 after restart -> REJECTED: nonce gap exceeds maximum: tx.nonce=102 currentNonce=0 maxGap=100`.

**Fix.** Replace the captured reference by a supplier: `mempool.setStateSupplier(() -> this.state);` and in `addInternal` `AccountState snap = stateSupplier == null ? null : stateSupplier.get();`.

### HC-030 — Merkle tree duplicates the odd leaf
**Severity:** MEDIUM — likelihood MEDIUM (any relaying peer) × impact MEDIUM (a node holding the mutated body rejects the committed hash → stall; storage keyed by hash) · **Status:** CONFIRMED · **Consensus-rule change:** YES

**PoC (executed).** 3 txs and 4 txs (last duplicated): identical `txRoot=0d3fc5…` and block hash `fdbb62…`.

**Fix.** Do not duplicate; promote the odd node, and domain-separate leaves from nodes:
```java
public static byte[] computeRoot(List<byte[]> leaves) {
    if (leaves == null || leaves.isEmpty()) return new byte[32];
    List<byte[]> level = new ArrayList<>();
    for (byte[] l : leaves) level.add(Crypto.hash(concat(new byte[]{0x00}, l)));
    while (level.size() > 1) {
        List<byte[]> next = new ArrayList<>();
        for (int i = 0; i < level.size(); i += 2) {
            if (i + 1 == level.size()) { next.add(level.get(i)); continue; }   // promote, never duplicate
            next.add(Crypto.hash(concat(new byte[]{0x01}, concat(level.get(i), level.get(i + 1)))));
        }
        level = next;
    }
    return level.get(0);
}
private static byte[] concat(byte[] a, byte[] b) {
    byte[] r = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, r, a.length, b.length);
    return r;
}
```
plus "a block must not contain the same txid twice" in `validateBlock`.

### HC-031 — Pruning deletes nothing; snapshots grow without bound
**Severity:** MEDIUM — likelihood CERTAIN with `MAX_BLOCKS` set × impact MEDIUM (SD-card exhaustion on edge nodes; history sync broken) · **Status:** CONFIRMED

**Description.** `PrunedBlockchain.pruneBlock` deletes `"block:"+hash` (`PrunedBlockchain.java:33`) but `Storage.saveBlock` writes `"block:"+H("block:"+hash)` (`Storage.java:554-557`). It does delete `height:<i>`, which breaks `REQUEST_BLOCKS` serving. Every block stores a full `state+utxo` snapshot (`Blockchain.java:861`) that nothing ever deletes; pruning even adds one more.

**PoC (executed).** `[HC-031] maxBlocks=2 after 6 blocks: block#1 body still on disk=true, height index for #1 deleted=true, full-state snapshots kept=7`.

**Fix.** Add `Storage.deleteBlock(String hash)` that deletes `blockStorageKey(hash)`; keep only the last `SNAPSHOT_RETENTION` (≥ finality depth) snapshots: `storage.del("snapshot:" + (h - SNAPSHOT_RETENTION))` after each save; keep the height index for retained blocks only.

### HC-034 — Live chain/state exposed without locking (LIKELY)
**Severity:** MEDIUM · **Status:** LIKELY (no PoC; race windows are small and timing-dependent)
`getChain()` returns the live `ArrayList` mutated under the write lock while REST handlers iterate it without the lock (`Blockchain.java:1534`); `getState()` hands out the live `AccountState` that the rollback/revert paths swap, and after `fromMap` its account map is a plain `HashMap` (`AccountState.java:58`); `paused`/`skipRateLimit` are non-volatile (`:32,216-219`). Expected effects: `ConcurrentModificationException` in API calls, stale reads. **Fix:** return immutable snapshots under the read lock (`List.copyOf(chain)`), use `ConcurrentHashMap` in both constructors, make the flags `volatile`.

### HC-032 — Fast-sync path defects (LIKELY, currently unreachable)
**Severity:** LOW · **Status:** LIKELY. The fast-sync branch (`Blockchain.java:127-188`) runs only when storage has a checkpoint but no tip and no snapshot height — unreachable with today's writers (a checkpoint is saved only by nodes that already have a tip, `PeerNode.java:327`). If reached: quorum `2f+1` repeats HC-002's root cause; on state-root mismatch, `state/utxo` are already replaced and `cpBlock` already appended, then genesis is appended → chain `[cpBlock, genesis]` with unverified state. **Fix:** compute everything into locals, commit only on success; use the HC-002 quorum `⌈(n+f+1)/2⌉`.

### HC-033 — Unchecked arithmetic and missing per-type checks (LIKELY)
**Severity:** LOW · **Status:** LIKELY. (a) `Account.credit/creditToken` use unchecked `Map.merge(a+b)` (`AccountState.java:690-702`); (b) `TokenRegistry.mintToken` checks `totalMinted + amount > maxSupply` with overflow; (c) `validateTransaction` has no `fee >= baseFee` check for CONTRACT, IOT_MANAGEMENT, FEDERATED_*, TOKEN_REGISTER/MINT/BURN, PRIVATE_TRANSFER, TELEMETRY_BATCH, and no fee-balance check for TOKEN_REGISTER/MINT/BURN; (d) `MerklePatriciaTrie.get(byte[])` does not hash while `put(byte[])`/`delete(byte[])` do; `toMap()` always returns an empty map. **Fix:** `Math.addExact` everywhere balances change; one shared `checkFeeAndNonce(tx, state)` called by every case; make `get(byte[])` hash or rename it `getByHashedKey`; delete `toMap/fromMap`.

### HC-035 — Checkpoint `utxoRoot` depends on HashMap iteration order (LIKELY)
**Severity:** LOW · **Status:** LIKELY. `Crypto.hash(utxo.toJSON().toString().getBytes())` (`Blockchain.java:891`) hashes a `HashMap`'s `toString()`. Iteration order depends on table capacity and insertion history (a restarted node rebuilds the map from JSON with a different capacity). Validators may sign different `utxoRoot`s for the same height; checkpoints then never reach quorum. **Fix:** hash a canonical encoding: sort keys (`new TreeMap<>(map)`), length-prefix each `key, address, amount`, UTF-8.

### Ledger — what was checked and found clean (selection; full list in the ledger)
- `MerklePatriciaTrie` insert/remove/hash: canonical form verified by `LedgerPropertyTest` (25 seeds × 8 permutations, delete-restores-root, clone round-trip) — all pass.
- S1-01 rollback (`applyBlockInternal` catch block) restores state/utxo/totalMinted correctly for a block rejected at the root check: `LedgerPropertyTest#rejectedBlockLeavesStateRootAndBalancesUnchanged` passes (10 seeds); the *storage* side effects are HC-024.
- `Transaction.serializeCanonical/signingPayload/verify`: length-prefixed, domain-separated, address bound to pubKey; Dilithium path gated by HC-008's per-node flag.
- `Tokenomics.getCurrentReward`: the `>>` wrap at 64 halvings is unreachable (cap reached ≈ height 1.9 M, wrap at 13.4 M).
- `FeeMarket.calculateNextBaseFee`: integer-only, floor 1 (determinism OK); the defect is where the value is stored (HC-022).

### Ledger — corrections
- HC-022 first run showed no `baseFee` divergence (both values 1): blocks below target keep `baseFee` at its floor. Divergence confirmed only for above-target blocks (`hc022b`).
- The planned candidate "fast-sync reachable after restart" (notes C-L2) was disproved: `lastSnapshotHeight` is written every block, so restart always takes the snapshot path; the fast-sync defects are recorded as LIKELY/unreachable (HC-032).
- Candidate "duplicate UTXO input double-counted": validation does double-count, but the second `spendOutput` throws during apply; the observable effect is the HC-017 fee credit plus a partial spend, so it is folded into HC-017/HC-018 rather than reported separately.
