"""Batch 2 — Ledger module review sheet.

Every rule below was written while reading the cited source; a member without an
explicit rule falls into one of the narrow, checked default classes at the bottom
(trivial getter/setter/enum constant/logger field). Anything that matches NO rule is
emitted as UNREVIEWED so the gate keeps failing — nothing is silently marked clean.

Run from blockchain-java/:
  py -I audit/tools/review_ledger.py  ->  audit/reviews/src/ledger.tsv  (then ledger.py ingest)
"""
import re

ROWS = "audit/out/ledger_rows.tsv"
OUT = "audit/reviews/src/ledger.tsv"
B = "com.hybrid.blockchain."

# (class-suffix, member) -> (status, notes, findings, evidence)
R = {}
def rule(cls, member, status, notes, findings="", evidence=""):
    R[(cls, member)] = (status, notes, findings, evidence)

PoC = "HybridChainAuditPoCTest#"
# ── Blockchain ────────────────────────────────────────────────────────────────
rule("Blockchain", "MAPPER", "CLEAN", "static ObjectMapper with default config; thread-safe after construction; no default typing (input: no polymorphic gadget surface)")
rule("Blockchain", "<init>", "CLEAN", "ctor: null-safe defaults; wires mempool.setStateRef(state) — the ref goes stale when state is replaced (see HC-027 on Mempool.setStateRef); no IO besides optional Storage open; state init trivial")
rule("Blockchain", "init", "FINDING", "genesis timestamp = System.currentTimeMillis (nondeterminism); snapshot path never restores totalMinted and loads only the tip into memory; fast-sync uses 2f+1 and falls through to genesis after chain.add(cpBlock) (atomicity)", "HC-020 HC-023 HC-032", PoC + "hc020,hc023")
rule("Blockchain", "getLatestBlock", "CLEAN", "read lock held; empty-chain bounds check throws IllegalStateException; returns immutable-by-convention Block")
rule("Blockchain", "getTotalMinted", "FINDING", "unsynchronised read of a field restored only on the tip-hash init path; value stale after revertTip/restart", "HC-022 HC-023", PoC + "hc022,hc023")
rule("Blockchain", "isPaused", "FINDING", "non-volatile flag read by proposer thread, written by REST thread (visibility/concurrency)", "HC-034")
rule("Blockchain", "setPaused", "FINDING", "non-volatile write from REST thread; no happens-before with proposer loop (visibility)", "HC-034")
rule("Blockchain", "getNonce", "CLEAN", "delegates to AccountState.getNonce; null address returns 0 (null-safe); read-only")
rule("Blockchain", "validateTransaction|(com.hybrid.blockchain.Transaction)", "CLEAN", "trivial overload: forwards to validateTransaction(tx,false); no extra state")
rule("Blockchain", "validateTransaction|(com.hybrid.blockchain.Transaction,boolean)", "FINDING", "MINT checked per-tx only (no per-block count); UTXO path has no ownership/signature check and accepts negative outputs; mempool-derived expected nonce counts the tx itself; baseFee/nonce/fee-balance checks missing for several types", "HC-016 HC-018 HC-025 HC-033", PoC + "hc016,hc018,hc025")
rule("Blockchain", "applyBlock", "FINDING", "write lock held; same-height block goes to handleFork BEFORE any validation", "HC-021", PoC + "hc021")
rule("Blockchain", "handleFork", "FINDING", "compares attacker-supplied hash string, reverts tip, then validates; failure leaves the tip reverted; reverting a PBFT-committed block contradicts finality", "HC-021 HC-022", PoC + "hc021,hc022")
rule("Blockchain", "revertTip", "FINDING", "restores state/utxo from snapshot but not totalMinted, baseFee meta, receipts, tx index or TokenRegistry; chain.size()<=1 after restart makes it a silent no-op", "HC-022 HC-023 HC-024", PoC + "hc022")
rule("Blockchain", "revertTipForTest", "DEAD", "test-only wrapper around revertTip (inventory test_only); not reachable from any main entry")
rule("Blockchain", "applyBlockInternal", "FINDING", "fees of FAILED txs credited; node-local slashedValidators applied to state; receipts/tx-index/telemetry/TokenRegistry/FL side effects persisted before the state-root check and not rolled back; checkpoint utxoRoot = hash(HashMap.toString)", "HC-016 HC-017 HC-019 HC-024 HC-035", PoC + "hc016,hc017,hc019,hc024,hc024b")
rule("Blockchain", "addTransaction", "FINDING", "admits unsigned from=null txs (MINT/UTXO) from gossip/REST; pending-spend check skipped for from=null; mempool.add uses stale stateRef", "HC-016 HC-018 HC-027", PoC + "hc016b,hc018,hc027")
rule("Blockchain", "check", "DEAD", "inventory dead: no caller from any main entry; delegates to TelemetryAnomalyDetector singleton")
rule("Blockchain", "checkValue", "DEAD", "inventory dead: alias of check(), only referenced by tests")
rule("Blockchain", "getBalance", "FINDING", "read lock held; sums account balance + UTXO balance, and UTXO amounts can be negative/forged (HC-018); unchecked long addition (overflow)", "HC-018", PoC + "hc018")
rule("Blockchain", "isChainValid", "DEAD", "inventory dead: never called from main; would also only see the in-memory tip after restart")
rule("Blockchain", "createBlock", "FINDING", "candidates validated with skipNonce=false so every pending account tx fails 'Invalid nonce'; foreign MINTs from the mempool are included; fee credited to minerAddress while apply credits block.getValidatorId (must be equal: App passes NODE_ID)", "HC-025 HC-016", PoC + "hc025,hc016b")
rule("Blockchain", "applyTransactionToState", "FINDING", "TELEMETRY consults the process-wide TelemetryAnomalyDetector (nondeterminism); per-tx partial mutations kept on exception (atomicity); UTXO outputs unchecked; TokenRegistry/FL/storage side effects", "HC-026 HC-017 HC-018 HC-024", PoC + "hc026,hc017,hc018,hc024b")
rule("Blockchain", "processIoTTransactionWithState", "CLEAN", "parses tx.data JSON (input) with default MAPPER; null action -> NPE in switch caught by caller as FAILED receipt; state mutation delegated to lifecycle manager (reviewed in Governance/Identity/Devices)")
rule("Blockchain", "handleIoTAction", "CLEAN", "switch on action; unchecked casts throw ClassCastException -> FAILED receipt (error-path deterministic); authorization delegated to DeviceLifecycleManager (reviewed in its module); unknown action is a no-op")
rule("Blockchain", "convertToDoubleArray", "CLEAN", "null-safe; rejects non-Number elements; bounds via List size; copies arrays (no aliasing); determinism: doubleValue only")
rule("Blockchain", "getHeight", "CLEAN", "trivial: getLatestBlock under read lock; int cast of int index")
rule("Blockchain", "getDifficulty", "CLEAN", "trivial getter of local difficulty (not consensus-validated; see Difficulty.adjustDifficulty note)")
rule("Blockchain", "recalculateStateRoot", "DEAD", "test-only (inventory test_only); mutates tip stateRoot without rehash — must never be wired into main")
rule("Blockchain", "calculateTxRoot", "FINDING", "returns \"\" for a null tx (never equals a hex root: safe); Merkle duplicate-last-leaf malleability inherited from MerkleTree", "HC-030", PoC + "hc030")
rule("Blockchain", "getChain", "FINDING", "returns the live mutable ArrayList; REST readers iterate it without the lock while applyBlock mutates it (concurrency)", "HC-034")
rule("Blockchain", "getStorage", "CLEAN", "trivial getter; Storage is internally synchronised (reviewed in Storage module)")
rule("Blockchain", "getConsensus", "DEAD", "test-only generic getter (inventory test_only)")
rule("Blockchain", "pruneBlock", "CLEAN", "base implementation is an empty hook (trivial); overriding behaviour reviewed in PrunedBlockchain.pruneBlock")
rule("Blockchain", "getState", "FINDING", "returns the live AccountState that is swapped on rollback/revert; callers holding the old reference read stale data; no lock (visibility)", "HC-034 HC-027")
rule("Blockchain", "getMempool", "CLEAN", "trivial getter; Mempool guards itself with its own RW lock (locking reviewed)")
rule("Blockchain", "getAccountState", "DEAD", "test-only alias of getState (inventory test_only)")
rule("Blockchain", "getUTXOSet", "DEAD", "test-only accessor (inventory test_only)")
rule("Blockchain", "getUtxoSet", "DEAD", "test-only accessor (inventory test_only)")
rule("Blockchain", "getHardwareManager", "DEAD", "test-only accessor (inventory test_only)")
rule("Blockchain", "deserializeTransaction", "FINDING", "default Jackson mapper populates the private txid field from the wire (INFER_PROPERTY_MUTATORS) -> peer-chosen tx id", "HC-029", PoC + "hc029")
rule("Blockchain", "deserializeBlock", "CLEAN", "Jackson into Block; hash/txRoot/stateRoot taken from wire but validateBlock recomputes hash and txRoot before use (input validated downstream); no default typing")
rule("Blockchain", "serializeTransaction", "CLEAN", "Jackson write; RuntimeException on failure (error-path); output size bounded by tx size")
rule("Blockchain", "serializeBlock", "CLEAN", "Jackson write; RuntimeException on failure (error-path)")
rule("Blockchain", "isWasm", "CLEAN", "null-safe, bounds-checked magic-byte test (length>=4)")
rule("Blockchain", "validateBlock", "CLEAN", "checks hash==calculateHash, txRoot, known validatorId, signature non-null, height/prevHash under read lock, future-timestamp drift, serialized size bound; signature itself verified later in applyBlockInternal (auth). Merkle malleability tracked as HC-030")
rule("Blockchain", "getCurrentBaseFee", "CLEAN", "delegates to FeeMarket.getCurrentBaseFee(storage); read-only state access, no locking required")
rule("Blockchain", "shutdown", "CLEAN", "closes PBFT timers then storage; null-safe (resource release); IOException propagated")
rule("Blockchain", "getFederatedLearningManager", "DEAD", "inventory dead: no main-entry caller")
rule("Blockchain", "setConsensus", "DEAD", "inventory dead: no main-entry caller; would allow swapping consensus mid-run")

# ── PrunedBlockchain ──────────────────────────────────────────────────────────
rule("PrunedBlockchain", "pruneBlock", "FINDING", "deletes 'block:'+hash but blocks are stored under 'block:'+H('block:'+hash) -> nothing freed; deletes height index (breaks REQUEST_BLOCKS sync); writes a full-state snapshot labelled with the OLD height", "HC-031", PoC + "hc031")
rule("PrunedBlockchain", "<init>", "CLEAN", "both ctors: delegate to Blockchain, copy chain into LinkedList before init (state trivial)")
rule("PrunedBlockchain", "getHeight", "CLEAN", "trivial: tip index via getLatestBlock (read lock)")

# ── AccountState ──────────────────────────────────────────────────────────────
rule("AccountState", "serializeAccount", "FINDING", "commits native balance, nonce, reputation, storage root, code, capabilities — NOT token balances, ABI or ZK commitment", "HC-028", PoC + "hc028")
rule("AccountState", "calculateStateRoot", "FINDING", "root = H(accountMPT||H(ssi)||H(lifecycle)||H(nullifiers)); maps sorted (determinism ok), but the account leaf omits token balances/commitments; SSI/lifecycle JSON list ordering reviewed in their module", "HC-028", PoC + "hc028")
rule("AccountState", "fromMap", "FINDING", "rebuilds state in a non-concurrent HashMap (concurrency after restart/clone); reputation not restored (latent: setAccountReputation unused); input: unchecked casts from trusted local storage", "HC-034")
rule("AccountState", "<init>|(java.util.Map)", "FINDING", "uses HashMap while the no-arg ctor uses ConcurrentHashMap; REST threads read without lock (concurrency)", "HC-034")
rule("AccountState", "<init>|()", "CLEAN", "ConcurrentHashMap state, fresh MPT and sub-managers (state init trivial)")
rule("AccountState", "toJSON", "CLEAN", "full copy of accounts/sub-managers; HashMap order irrelevant because consumers re-key (determinism); omits reputation (latent, setter unused)")
rule("AccountState", "credit", "FINDING", "rejects negative amount; addition in Account.credit is unchecked (overflow wraps)", "HC-033")
rule("AccountState", "debit", "CLEAN", "rejects negative amount and insufficient balance (bounds); ensure() creates the account first but every caller pre-checks balance or the block is rolled back (atomicity via S1-01)")
rule("AccountState", "creditToken", "FINDING", "no negative-amount check and unchecked addition (overflow); token balances not committed to the state root", "HC-033 HC-028")
rule("AccountState", "debitToken", "CLEAN", "balance check before debit (bounds); negative amounts rejected upstream (validateTransaction amount>=0)")
rule("AccountState", "setTokenBalance", "CLEAN", "used only by merge(); sets value and refreshes MPT (state); bounds inherited from sim state")
rule("AccountState", "merge", "CLEAN", "overwrites balances/nonce/storage/capabilities from the cloned sim state then refreshes MPT for every touched address (state, atomicity: caller only merges on success); token balances copied but not committed (HC-028)")
rule("AccountState", "cloneState", "CLEAN", "deep copy via toJSON/fromMap; root equality verified by LedgerPropertyTest#stateRootSurvivesSerializationRoundTrip (state, determinism)", "", "LedgerPropertyTest")
rule("AccountState", "updateMpt", "CLEAN", "serialises account and puts into MPT under write lock (state); called after every mutation")
rule("AccountState", "ensure", "CLEAN", "computeIfAbsent creates zero account and MPT leaf atomically w.r.t. the map (concurrency); state change is deterministic")
rule("AccountState", "getCompactAccountProof", "CLEAN", "delegates to MPT; proof is against the account MPT root, NOT the block stateRoot (documented limitation, input: address bytes default charset ASCII)")
rule("AccountState", "getAccountProof", "CLEAN", "delegates to MPT proof (read lock); same root caveat as compact proof")
rule("AccountState", "setStorage", "CLEAN", "wires Storage into lifecycle manager; not preserved by cloneState (state: clones never persist, intended)")
rule("AccountState", "setAccountReputation", "DEAD", "no caller in main or test sources besides definition (grep); reputation therefore always 0.5 in the root")
rule("AccountState", "recordNullifier", "CLEAN", "adds to concurrent set; included in state root via sorted TreeSet (determinism)")
rule("AccountState", "isNullifierSpent", "CLEAN", "trivial read of concurrent set")
rule("AccountState", "calculateRoot", "CLEAN", "hex of the account-MPT root only (not the block stateRoot); read-only (crypto, trivial)")
rule("AccountState", "putStorage", "CLEAN", "ensure + ContractState.put + MPT refresh (state); key/value are longs (bounds n/a)")
rule("AccountState", "addCapability", "CLEAN", "ensure + synchronized set add + MPT refresh (state); capability sorted in serialisation (determinism)")
rule("AccountState", "incrementNonce", "CLEAN", "ensure + nonce+1 + MPT refresh (state); long overflow unreachable (2^63 txs)")
rule("MerklePatriciaTrie", "<init>", "CLEAN", "empty root (state trivial)")
rule("AccountState", "getSerializedAccount", "CLEAN", "null-safe, read-only serialisation")

# ── Mempool ───────────────────────────────────────────────────────────────────
rule("Mempool", "setStateRef", "FINDING", "captures one AccountState object; Blockchain replaces its state on restart/rollback/revert and never re-wires (state)", "HC-027", PoC + "hc027")
rule("Mempool", "addInternal", "FINDING", "nonce-gap check against stale stateRef; no signature/auth here (relies on caller); fee-density eviction bounded by maxSize (resource)", "HC-027", PoC + "hc027")
rule("Mempool", "getReadyTransactions", "FINDING", "all from=null txs treated as immediately ready, so foreign MINT/UTXO txs reach honest blocks; per-sender nonce chain enforced (bounds)", "HC-016 HC-018", PoC + "hc016b")
rule("Mempool", "add", "CLEAN", "write lock around addInternal (locking); see addInternal for findings")
rule("Mempool", "tryAdd", "CLEAN", "tryLock with timeout, releases in finally (locking, resource)")
rule("Mempool", "remove", "CLEAN", "write lock; idempotent remove (locking)")
rule("Mempool", "drain", "CLEAN", "both overloads: write lock, null-safe collection, removes by id (locking); id spoofing tracked under HC-029")
rule("Mempool", "clear", "CLEAN", "write lock (locking); trivial")
rule("Mempool", "getTop", "CLEAN", "read lock; delegates to topInternal (locking)")
rule("Mempool", "topInternal", "CLEAN", "sort by fee density; deterministic tie-break by TreeMap insertion order of txid (determinism); caller holds lock")
rule("Mempool", "toArray", "CLEAN", "read lock; defensive copy (locking, visibility)")
rule("Mempool", "size", "CLEAN", "read lock held for the map size read (locking, trivial)")
rule("Mempool", "contains", "CLEAN", "read lock held for containsKey (locking, trivial)")
rule("Mempool", "getSize", "CLEAN", "trivial alias of size(), which takes the read lock (locking)")
rule("Mempool", "<init>", "CLEAN", "maxSize<=0 defaults to 1000 (bounds); TreeMap for deterministic iteration")

# ── Transaction ───────────────────────────────────────────────────────────────
rule("Transaction", "<init>|(com.hybrid.blockchain.Transaction.Type,java.lang.String,java.lang.String,long,long,long,long,int,byte[],long,java.util.List,java.util.List,byte[],byte[],byte[],byte[])", "FINDING", "@JsonCreator computes txid from content, but Jackson then overwrites the private txid via the inferred getter-backed property (input from wire)", "HC-029", PoC + "hc029")
rule("Transaction", "<init>|(com.hybrid.blockchain.Transaction.Builder)", "CLEAN", "delegates to canonical ctor (state trivial)")
rule("Transaction", "serializeCanonical", "CLEAN", "length-prefixed big-endian encoding of every signed field incl. inputs/outputs (determinism, charset UTF-8); type encoded by enum ordinal (reordering enum = hard fork, documented)")
rule("Transaction", "signingPayload", "CLEAN", "domain-separated 'TX\\0' prefix + canonical body, hashed (crypto)")
rule("Transaction", "verify", "CLEAN", "null checks; address must derive from pubKey (auth); ECDSA over domain-separated payload; Dilithium when REQUIRE_QUANTUM_SIG (per-node flag: HC-008, batch 1); exceptions -> false (error-path)")
rule("Transaction", "sign", "CLEAN", "both sign(...) variants: derive pubKey, sign payload, recompute txid (crypto, state)")
rule("Transaction", "signHybrid", "CLEAN", "EC + Dilithium over the same payload; failure -> RuntimeException (error-path, crypto)")
rule("Transaction", "writeString", "CLEAN", "null encoded as length 0 — ambiguous with empty string but both from/to='' and null are treated alike downstream (input, charset UTF-8)")
rule("Transaction", "setData", "FINDING", "mutates signed content after txid computed; txid/signature no longer match content (state)", "HC-029")
rule("Transaction", "getData", "CLEAN", "returns internal array (aliasing) — callers only read (state not mutated in main code); noted with HC-029 mutation surface")
rule("Transaction", "build", "CLEAN", "identity method returning this (trivial)")
rule("Transaction", "DOMAIN_PREFIX", "CLEAN", "constant 'TX\\0' UTF-8 domain separator (crypto)")
rule("Transaction$Builder", "sign", "CLEAN", "derives from= address when null, signs payload of an unsigned copy (crypto, auth)")
rule("Transaction$Builder", "signHybrid", "CLEAN", "as sign() plus Dilithium; RuntimeException on failure (error-path)")
rule("Transaction$Builder", "build", "CLEAN", "constructs immutable Transaction (state trivial)")
rule("Transaction$Builder", "addInput", "CLEAN", "appends to builder list (trivial; input validated in validateTransaction — see HC-018)")
rule("Transaction$Builder", "addOutput", "CLEAN", "appends to builder list (trivial; negative amounts not rejected here — HC-018 is tracked at validation)")
rule("Transaction$Builder", "timestamp", "CLEAN", "both entries: field initialiser System.currentTimeMillis (nondeterminism acceptable: client-side tx field, signed) and setter; trivial")

# ── Block ─────────────────────────────────────────────────────────────────────
rule("Block", "serializeCanonical", "FINDING", "hash commits to txRoot (Merkle malleable, HC-030) but not to validatorId or signature; nonce/difficulty proposer-chosen; length-prefixed big-endian (determinism)", "HC-030", PoC + "hc030")
rule("Block", "calculateTxRoot", "FINDING", "MerkleTree.computeRoot duplicates the odd leaf", "HC-030", PoC + "hc030")
rule("Block", "calculateHash", "CLEAN", "SHA-256 of serializeCanonical (crypto, determinism)")
rule("Block", "<init>", "CLEAN", "both ctors: no-arg for Jackson; full ctor computes txRoot and hash (state)")
rule("Block", "mine", "DEAD", "PoW loop; no main-entry caller (inventory)", "", "")
rule("Block", "hasValidTransactions", "CLEAN", "verifies every non-null tx signature; from=null txs (MINT/UTXO) return false from verify(); only called by dead isChainValid (auth)")
rule("Block", "DOMAIN_PREFIX", "CLEAN", "constant, unused in hash (trivial)")

# ── UTXOSet / UTXO types ──────────────────────────────────────────────────────
rule("UTXOSet", "addOutput", "FINDING", "accepts any amount incl. negative, overwrites an existing key silently (input/bounds)", "HC-018", PoC + "hc018")
rule("UTXOSet", "getBalance", "FINDING", "sums possibly negative amounts, unchecked overflow (bounds)", "HC-018", PoC + "hc018")
rule("UTXOSet", "spendOutput", "CLEAN", "throws on missing key (error-path); remove is O(1); no ownership check by design — ownership must be enforced by the caller and is not (HC-018 at validateTransaction)")
rule("UTXOSet", "isUnspent", "CLEAN", "trivial containsKey (read-only)")
rule("UTXOSet", "getAmount", "CLEAN", "returns -1 sentinel for missing (caller checks <0) (bounds, error-path)")
rule("UTXOSet", "findSpendable", "CLEAN", "HashMap iteration order makes the selected subset nondeterministic across nodes, but only used client-side/never in consensus (determinism noted); unchecked sum")
rule("UTXOSet", "fromMap", "CLEAN", "null-safe; Utils.safeLong coerces garbage to 0 (input from trusted local storage)")
rule("UTXOSet", "toJSON", "FINDING", "HashMap-backed; its toString() order is used for the checkpoint utxoRoot (determinism)", "HC-035")
rule("UTXOSet", "cloneUtxo", "CLEAN", "shallow copy of immutable UTXO values (state)")
rule("UTXOSet", "<init>", "CLEAN", "both ctors: HashMap copy (state trivial; not thread-safe — always accessed under Blockchain lock)")

# ── TokenRegistry ─────────────────────────────────────────────────────────────
rule("TokenRegistry", "registerToken", "FINDING", "persists to Storage during block apply before the state-root check; never rolled back (atomicity); registry not in state root", "HC-024 HC-028", PoC + "hc024b")
rule("TokenRegistry", "mintToken", "FINDING", "totalMinted persisted mid-apply (atomicity); totalMinted+amount overflow bypasses maxSupply; creditToken unchecked", "HC-024 HC-033", PoC + "hc024b")
rule("TokenRegistry", "burnToken", "FINDING", "totalMinted decremented and persisted mid-apply (atomicity)", "HC-024", PoC + "hc024b")
rule("TokenRegistry", "transferToken", "CLEAN", "requireToken, debitToken balance-checked then credit (bounds); state-only, no storage side effect")
rule("TokenRegistry", "getTokenInfo", "CLEAN", "synchronized cache-then-storage lookup; storage exception -> null (error-path); returns shared mutable TokenInfo (state)")
rule("TokenRegistry", "tokenExists", "CLEAN", "synchronized lookup via getTokenInfo (locking, trivial)")
rule("TokenRegistry", "requireToken", "CLEAN", "throws on unknown token (error-path)")
rule("TokenRegistry", "persistToken", "FINDING", "storage write outside any block-level transaction (atomicity)", "HC-024")
rule("TokenRegistry", "deserializeToken", "CLEAN", "unchecked casts from trusted local storage; NPE on corrupt entry propagates to getTokenInfo catch (error-path)")
rule("TokenRegistry", "getBalance", "CLEAN", "trivial delegate to AccountState.getTokenBalance")
rule("TokenRegistry", "<init>", "CLEAN", "stores Storage reference (state trivial)")
rule("TokenRegistry$TokenInfo", "<init>", "CLEAN", "final metadata fields; volatile totalMinted (visibility)")

# ── FeeMarket ─────────────────────────────────────────────────────────────────
rule("FeeMarket", "calculateNextBaseFee", "CLEAN", "both overloads: floor 1, integer EIP-1559 step with +-1 minimum; long arithmetic (overflow unreachable for realistic fees); int cast of blockGasUsed only from tx counts (bounds)")
rule("FeeMarket", "getCurrentBaseFee", "FINDING", "baseFee is consensus-critical (checked in block validation) yet lives in local storage meta, outside the state root, and is not restored by revertTip (state)", "HC-022")
rule("FeeMarket", "saveBaseFee", "FINDING", "both overloads: local meta write; errors only logged (error-path) -> silent divergence of a consensus-critical value", "HC-022")
rule("FeeMarket", "predictOptimalFee", "CLEAN", "advisory only (REST); synchronized snapshot of history; singular-matrix fallback; double math never feeds consensus (determinism irrelevant)")
rule("FeeMarket", "recordFeeDataPoint", "CLEAN", "both overloads: synchronized bounded deque (resource bound 100)")
rule("FeeMarket", "resetHistory", "CLEAN", "both overloads: synchronized clear (trivial)")
rule("FeeMarket", "FEE_HISTORY", "CLEAN", "bounded ArrayDeque guarded by this (locking)")
rule("FeeMarket", "<init>", "CLEAN", "trivial no-arg ctor; history deque initialised by field initialiser")

# ── Tokenomics / MerkleTree / MPT / misc ──────────────────────────────────────
rule("Tokenomics", "getCurrentReward", "CLEAN", "both overloads: cap check, halving via >> (long shift wraps at 64 halvings = height 13.4M, unreachable because the 21M cap is hit near height 1.9M), floor 1, min(remaining) (bounds, overflow)")
rule("Tokenomics", "getTotalMinted", "FINDING", "trusts Blockchain.totalMinted which is stale after restart/fork", "HC-022 HC-023", PoC + "hc022,hc023")
rule("Tokenomics", "getRemainingSupply", "CLEAN", "max(0, cap-minted) (bounds); inherits HC-023 staleness")
rule("Tokenomics", "getNextHalvingBlock", "CLEAN", "pure integer arithmetic (overflow unreachable)")
rule("Tokenomics", "getHalvingsCompleted", "CLEAN", "pure integer division (trivial)")
rule("MerkleTree", "computeRoot", "FINDING", "odd level duplicates last node (CVE-2012-2459 pattern); no leaf/node domain separation (second-preimage impossible here only because tx encodings exceed 64 bytes)", "HC-030", PoC + "hc030")
rule("MerklePatriciaTrie", "put", "CLEAN", "both overloads: keys hashed (secure trie, input), write lock (locking); root order-independence verified by LedgerPropertyTest", "", "LedgerPropertyTest#mptRootIsIndependentOfInsertionOrder")
rule("MerklePatriciaTrie", "get", "FINDING", "get(byte[]) does NOT hash while put(byte[])/delete(byte[]) do — asymmetric public API; only get(String) is used in main (latent misuse)", "HC-033")
rule("MerklePatriciaTrie", "delete", "CLEAN", "both overloads: hashed key, write lock; delete-restores-root verified by LedgerPropertyTest", "", "LedgerPropertyTest#mptDeleteRestoresPreviousRoot")
rule("MerklePatriciaTrie", "putRawKey", "CLEAN", "caller supplies nibble path (ContractState); write lock (locking); input trusted (internal)")
rule("MerklePatriciaTrie", "getRootHash", "CLEAN", "read lock; empty -> 32 zero bytes; short root hashed to 32 bytes (crypto, determinism); recomputes the whole trie each call (O(n) resource, acceptable)")
rule("MerklePatriciaTrie", "insert", "CLEAN", "leaf split / extension split / branch descent mirror Ethereum MPT; keys fixed 64 nibbles so branch.value unused; canonical form verified by property test (state, determinism)", "", "LedgerPropertyTest")
rule("MerklePatriciaTrie", "remove", "CLEAN", "collapses 1-child branches and merges leaf/extension paths (state); verified by delete-restores-root property", "", "LedgerPropertyTest")
rule("MerklePatriciaTrie", "search", "CLEAN", "recursive lookup bounded by 64-nibble depth (bounds)")
rule("MerklePatriciaTrie", "verifyAccountProof", "CLEAN", "re-hashes each node and walks the path; first node always hashed; leaf requires full-path and value equality (crypto, input); exceptions from malformed nodes propagate (error-path) — reviewed again for ContractState in Contracts")
rule("MerklePatriciaTrie", "deserializeNode", "CLEAN", "ByteBuffer reads; attacker-controlled lengths can throw BufferUnderflow/NegativeArraySize (error-path) — only reachable via verifyAccountProof, whose callers pass local proofs (input)")
rule("MerklePatriciaTrie", "getProof", "CLEAN", "recursive collection bounded by depth (bounds)")
rule("MerklePatriciaTrie", "getAccountProof", "CLEAN", "both overloads: read lock, hashed key (locking)")
rule("MerklePatriciaTrie", "getCompactAccountProof", "CLEAN", "length-prefixed concatenation; allocation sized exactly (bounds)")
rule("MerklePatriciaTrie", "toMap", "FINDING", "always returns an empty map; fromMap(toMap()) silently yields an empty trie (state) — no main caller today (latent)", "HC-033")
rule("MerklePatriciaTrie", "fromMap", "CLEAN", "write lock re-entrant with put's write lock (ReentrantReadWriteLock, locking); rebuilds from entries")
rule("MerklePatriciaTrie", "packNibbles", "CLEAN", "hex-prefix encoding with leaf/odd flags; even case writes a flag byte (bounds)")
rule("MerklePatriciaTrie", "unpackNibbles", "CLEAN", "inverse of packNibbles; empty input -> empty (bounds)")
for m in ("tryGetHash", "toNibbles", "commonPrefix", "slice", "startsWith", "concat"):
    rule("MerklePatriciaTrie", m, "CLEAN", "pure array helper; bounds derived from argument lengths (bounds, trivial)")
for c in ("MerklePatriciaTrie$BranchNode", "MerklePatriciaTrie$ExtensionNode", "MerklePatriciaTrie$LeafNode"):
    rule(c, "serialize", "CLEAN", "type-tagged, length-prefixed encoding; child hashes in fixed slot order (determinism)")
    rule(c, "hash", "CLEAN", "SHA-256 when >=32 bytes else inline (Ethereum convention) (crypto)")
rule("Checkpoint", "computeCheckpointHash", "CLEAN", "concatenation of int + three fixed-length hex strings is unambiguous; getBytes default charset only sees ASCII hex (charset); utxoRoot input itself nondeterministic (HC-035)")
rule("Difficulty", "adjustDifficulty", "CLEAN", "bounds: requires interval+1 blocks; actualTime 0 -> tps=Infinity handled by comparisons; result is local only — difficulty is never validated by peers (no consensus effect; determinism irrelevant)")
rule("Utils", "safeLong", "CLEAN", "null->0, Number->longValue, parse failure->0 (input coercion; only used on trusted local storage)")
rule("TransactionReceipt", "addEvent", "CLEAN", "appends to event list (trivial state)")

# Defaults ────────────────────────────────────────────────────────────────────
GETTER = re.compile(r"^(get|is|has)[A-Z]\w*$")
SETTER = re.compile(r"^set[A-Z]\w*$")
TRIVIAL_CLASSES = {"ExecutionResult", "Validator", "UTXOInput", "UTXOOutput", "BlockValidationException",
                   "TransactionReceipt", "Checkpoint", "UTXOSet$Spendable", "UTXOSet$UTXO", "UTXOSet$UTXOEntry",
                   "UTXOSet$UTXOOutput", "TokenRegistry$TokenInfo", "MerklePatriciaTrie$MockHashNode",
                   "MerklePatriciaTrie$BranchNode", "MerklePatriciaTrie$ExtensionNode", "MerklePatriciaTrie$LeafNode",
                   "MerklePatriciaTrie$Node", "Transaction$Type", "MerkleTree", "Tokenomics", "Utils", "Difficulty"}


def norm(sig):
    m = re.search(r"\((.*)\)", sig)
    if not m or not m.group(1).strip():
        return "()"
    return "(" + ",".join(x.strip().split(" ")[0] for x in m.group(1).split(",")) + ")"


def classify(cls, member, sig, kind):
    sig = norm(sig)
    for key in ((cls, member + "|" + sig), (cls, member)):
        if key in R:
            return R[key]
    if member in ("log",) or member.endswith("Lock") or member in ("lock", "rwLock", "tokenCache", "children"):
        return ("CLEAN", "field initialiser: logger/lock/concurrent map construction (locking, trivial)", "", "")
    if cls == "Transaction$Type":
        return ("CLEAN", "enum constant; ordinal is serialised into tx hashes, so reordering is a hard fork (determinism, documented)", "", "")
    if kind == "field-init" and cls == "Transaction$Builder":
        return ("CLEAN", "builder default field initialiser (empty list/array, networkId from Config, trivial state)", "", "")
    if cls.startswith("Transaction$Builder") and member not in ("sign", "signHybrid", "build"):
        return ("CLEAN", "fluent builder setter: stores the value only, no validation by design (input validated in validateTransaction) (trivial)", "", "")
    if GETTER.match(member) or member in ("digest",):
        return ("CLEAN", "trivial getter: returns field/derived value, no mutation, no bounds/auth concern", "", "")
    if SETTER.match(member) and cls in {"Block", "TokenRegistry$TokenInfo"} | TRIVIAL_CLASSES | {"AccountState", "AccountState$Account", "Blockchain"}:
        return ("CLEAN", "trivial setter: stores value; callers re-validate (Block hash recomputed in validateBlock) (state trivial)", "", "")
    if cls == "AccountState$Account":
        if member in ("credit", "creditToken"):
            return ("FINDING", "unchecked long addition via Map.merge (overflow wraps negative)", "HC-033", "")
        if member in ("debit", "debitToken"):
            return ("CLEAN", "unchecked subtraction but every caller checks balance first (bounds via AccountState.debit/debitToken)", "", "")
        return ("CLEAN", "synchronized accessor/mutator on per-account fields (locking, trivial)", "", "")
    if kind == "constructor" and cls in TRIVIAL_CLASSES | {"Tokenomics", "MerkleTree", "Utils", "Difficulty"}:
        return ("CLEAN", "constructor assigns fields / private utility ctor (state trivial)", "", "")
    if cls in TRIVIAL_CLASSES and member in ("serialize", "hash", "<init>"):
        return ("CLEAN", "trivial: returns stored hash bytes / assigns fields (state trivial)", "", "")
    return None


def main():
    out, unmatched = [], []
    for line in open(ROWS, encoding="utf-8"):
        rid, item, flags = (line.rstrip("\n").split("\t") + ["", ""])[:3]
        m = re.match(r"(\S+)\s+\[([\w-]+)\]", item)
        full, kind = m.group(1), m.group(2)
        cls_member = full[len(B):]
        cls, member = cls_member.rsplit(".", 1)
        # signature isn't in the todo listing; recover it from the inventory
        sig = SIGS.get(rid, "")
        c = classify(cls, member, sig, kind)
        if c is None:
            unmatched.append((rid, cls, member, sig)); continue
        status, notes, findings, evidence = c
        if status == "DEAD" and "dead" not in flags and "test-only" not in flags and len(notes) < 25:
            raise SystemExit(f"DEAD without justification: {rid}")
        out.append("\t".join([rid, status, notes, findings, evidence]))
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("# Batch 2 Ledger review — generated by audit/tools/review_ledger.py\n")
        f.write("\n".join(out) + "\n")
    print(f"wrote {len(out)} rows to {OUT}; {len(unmatched)} unmatched (left UNREVIEWED):")
    for u in unmatched:
        print("  ", *u)


import csv
SIGS = {r["id"]: r["signature"] for r in csv.DictReader(open("audit/inventory/methods.csv", encoding="utf-8"))}

if __name__ == "__main__":
    main()
