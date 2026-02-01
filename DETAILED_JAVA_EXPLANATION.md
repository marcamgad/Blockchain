DETAILED JAVa SOURCE EXPLANATIONS

This document explains the Java sources in the `blockchain-java` module in deep, developer-focused detail. Each file section covers purpose, responsibilities, important methods, data flows, security considerations, and notable caveats or edge cases.

**How to use**
- File links point to workspace-relative paths. Open the file to inspect implementation details.

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/Blockchain.java`**
- Purpose: Core runtime for the node. Manages the in-memory chain (`List<Block>`), UTXO set (`UTXOSet`), account model (`AccountState`), `Mempool`, persistence via `Storage`, difficulty, pending transactions, and integration with a `PoAConsensus` instance and `HardwareManager` for deferred physical actions.
- Key responsibilities:
  - Initialization/resume from `Storage` using either snapshots (`lastSnapshotHeight`) or tip-hash recovery.
  - Genesis creation when no persisted state exists.
  - Transaction validation (`validateTransaction`) for UTXO, ACCOUNT, and CONTRACT types.
  - Block application (`applyBlock`): verify PoA validator, validate transactions, update UTXO and account state, execute smart-contract transactions via `Interpreter`, commit deferred physical actions once blocks reach 6 confirmations, persist block/state/utxo, prune blocks via `pruneBlock`, and adjust difficulty.
  - Block creation (`createBlock`): selects top mempool transactions, filters by validation, appends a miner reward `Transaction`, constructs a `Block`, mines it (leading-zero prefix difficulty), and returns it.
- Important methods and behavior:
  - `init()`: two recovery paths + genesis creation. Loads `difficulty` meta if present.
  - `validateTransaction(Transaction tx)`: signature verification, network id check, negative amount/fee check, per-model validation (UTXO inputs present, account nonce and balance checks, contract availability check).
  - `applyBlock(Block block)`: enforces chain linking, PoA validator verification (`poaConsensus`), verifies all txs, applies UTXO spends and outputs, account debits/credits and nonce increments, runs contract VM (fee->gas multiplier), persists to `Storage`, calls `pruneBlock`, and re-calculates difficulty using `Difficulty.adjustDifficulty` at intervals.
  - `addTransaction(Transaction tx)`: basic checks then pushes into `pendingTransactions` (not same as mempool - mempool is separate runtime structure).
- Data flows:
  - State/UTXO persist in `Storage` using JSON-like maps.
  - Contract execution receives an `Interpreter.BlockchainContext` with `AccountState` and `HardwareManager` references.
- Security and concurrency notes:
  - No explicit synchronization; callers are expected to manage concurrency. API wrappers use locks (e.g., `IoTRestAPI`).
  - PoA validator verification is mandatory for `applyBlock`.
  - Contract execution gas model: `fee * 1000` passed to `Interpreter` (ensure gas accounting and starvation-handling are reviewed).
- Caveats:
  - Mining uses a simple leading-zero prefix string comparison that scales poorly for high difficulty values (string creation per iteration). Also `MAX_NONCE_ATTEMPTS` guards nonce exhaustion.
  - `pruneBlock` is an extension point and no-op in `Blockchain` base class.

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/Block.java`**
- Purpose: Block domain object. Contains block fields and canonical serialization for hashing and signing.
- Important fields: `index`, `timestamp`, `prevHash`, `transactions`, `nonce`, `difficulty`, `hash`, `stateRoot`, `validatorId`, `signature`.
- Key methods:
  - `serializeCanonical()`: deterministic serialization layout: index, timestamp, prevHash (len + bytes), nonce, difficulty, stateRoot (len + bytes), tx count, then txs (len + bytes each). Uses `Config.MAX_BLOCK_SIZE` fixed ByteBuffer allocation.
  - `calculateHash()`: SHA-256 of `serializeCanonical()` hex-encoded.
  - `mine(int targetDifficulty, long maxNonce)`: increments nonce until hash startsWith("0" * difficulty). Throws RuntimeException if nonce exceeds `maxNonce`.
  - `hasValidTransactions()`: verifies signatures for all transactions.
- Security/caveats:
  - Block hash includes full transactions and stateRoot (no separate merkle root), which is simpler but increases serialized size and hashing cost.
  - Use of fixed-size ByteBuffer for serialization requires `Config.MAX_BLOCK_SIZE` to be large enough to hold serialized block.

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/Transaction.java`**
- Purpose: Immutable transaction object supporting hybrid models: `ACCOUNT`, `UTXO`, and `CONTRACT`.
- Construction: `Builder` pattern. `sign(BigInteger privateKey, byte[] publicKey)` attaches `pubKey`, creates signature, and sets `from` address derived from public key.
- Canonical serialization: used for txid generation and signing payload. `serializeCanonical()` writes version, type, networkId, nonce, timestamps, validUntilBlock, from/to strings, amount, fee, data, inputs, outputs.
- Signing & verification:
  - `signingPayload()` returns hash(DOMAIN_PREFIX || serializeCanonical()). Domain separation helps prevent cross-domain replay.
  - `verify()` checks presence of `signature` and `pubKey`, derives `from` from `pubKey` and compares to stored `from`, then verifies signature using `Crypto.verify`.
- Notable design choices & caveats:
  - `txid` is hash of the canonical serialization but excludes signature/pubKey (common practice). This implies txid independence from signature (malleability via signature must be prevented by canonical signatures — `Crypto.sign` produces low-S normalized signatures).
  - Buffer allocation is fixed (8192); large contract `data` may exceed this and cause errors.
  - Builder `sign()` mutates builder fields (pubKey, signature, from) then returns a new Transaction — reuse of the builder after sign can be surprising.

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/AccountState.java`**
- Purpose: Tracks account balances/nonces, contract `ContractState` storage per account, and integrates higher-level managers: `SSIManager`, `DeviceLifecycleManager`, `PrivateDataManager`.
- Data model: Map<String, Account> where `Account` contains `balance`, `nonce`, `ContractState storage`, and `Set<Capability>`.
- Important methods:
  - `credit`, `debit` (throws on insufficient funds or negative amounts), `incrementNonce`, `setNonce`.
  - `getAccountStorage`, `getAccountCapabilities` to access per-account contract storage and capability sets.
  - `calculateStateRoot()` and `serializeCanonical()`: deterministic serialization for state root computation used in `Block`.
- Serialization details:
  - Deterministic ordering by sorting addresses, including contract storage root (decoded hex), and sorted capabilities.
  - Uses a fixed 1MB ByteBuffer — potentially too small for large states.
- Managers:
  - `SSIManager` for DID/VCs, `DeviceLifecycleManager` for provisioning and lifecycle management, `PrivateDataManager` for private collections.
- Caveats and security:
  - `debit` throws an Exception on insufficient funds — callers must catch and handle.
  - Fixed buffer sizes can cause trouble at scale; consider streaming serialization.

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/UTXOSet.java`**
- Purpose: Manage UTXO model subset. Stores unspent outputs keyed by `txid:index`.
- API highlights: `addOutput`, `spendOutput` (throws if missing), `isUnspent`, `findSpendable` (naive coin selection), `getBalance`, `toJSON`/`fromMap` for persistence.
- Complexity: Linear scans for address balance and coin selection — fine for small sets, but heavy at scale. Consider indexing by address for performance.
- Edge-cases: No fork/reorg handling here; the caller must rebuild UTXO set on reorgs.

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/Mempool.java`**
- Purpose: In-memory prioritized pool for pending transactions.
- Key behaviors:
  - `add(Transaction tx)`: timestamp validity (±1 day), uniqueness check, replacement rule for ACCOUNT txs (same `from` + `nonce`, replacement only if new fee higher), eviction if pool full by removing lowest fee-per-byte tx if incoming tx has higher fee-per-byte.
  - `getTop(int n)`: returns top `n` by fee-per-byte descending.
- Implementation notes:
  - Fee-per-byte uses `tx.serializeCanonical().length` for size estimation.
  - Replacement only supports ACCOUNT txs with identical nonce — UTXO replacements not handled.
  - No persistence; mempool lost on restart.

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/Crypto.java`**
- Purpose: Cryptographic primitives wrapper (SHA-256 hashing, ECDSA signing/verification using secp256k1 via BouncyCastle), address derivation, and small helpers for byte/long conversion.
- Key details:
  - Uses BouncyCastle's `secp256k1` curve through `CustomNamedCurves` and `ECDSASigner` with `HMacDSAKCalculator` for deterministic nonces.
  - `sign(byte[] message, BigInteger privateKey)`: returns 64-byte R|S concatenation with low-S normalization to prevent malleability.
  - `verify(byte[] message, byte[] signature, byte[] pubKey)`: decodes compressed EC point, verifies R|S after low-S normalizing.
  - `deriveAddress(byte[] pubKey)`: returns `hb` + first 20 bytes of SHA-256(pubKey) hex — application-level address scheme (not Ethereum-like RIPEMD160).
- Security notes:
  - Low-S normalization reduces signature malleability.
  - `derivePublicKey(BigInteger privateKey)` returns compressed EC point.

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/PoAConsensus.java`**
- Purpose: Simple Proof-of-Authority signing and verification layer for blocks; stores static `List<Validator>` with ids and public keys.
- Key methods: `signBlock(Block block, Validator validator, BigInteger privateKey)` and `verifyBlock(Block block, Validator validator)` which sign/verify `signingPayload(block)` (domain-prefixed hash of canonical serialization).
- Notes: Authority set is static and provided at construction. `isValidator` checks membership.

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/consensus/PBFTConsensus.java`**
- Purpose: Prototype PBFT implementation for private validator sets.
- Features implemented:
  - Message structures (`PBFTMessage`) with phases PRE_PREPARE, PREPARE, COMMIT.
  - View/leader selection (round-robin deterministic ordering of validators).
  - Message logging and quorum checks (2f+1) for prepare/commit phases.
- Important caveats:
  - This is a simplified / partially simulated implementation: network messaging is not implemented; methods such as `validateBlock` simulate vote counts and expect external callers to call `addPrepareVote` and `addCommitVote`.
  - `selectLeader` is implemented for view selection; note `selectLeader(List<String>, long)` from `Consensus` interface is unused and returns `null` (an implementation mismatch).

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/Validator.java`**
- Tiny data holder for `id` and `publicKey` bytes. Used by `PoAConsensus` and `IoTRestAPI`.

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/Storage.java`**
- Purpose: Encrypted persistence layer using LevelDB + AES encryption + Jackson for JSON serialization.
- Key features:
  - AES key required (16/24/32 bytes). Uses AES ECB in the code (note: ECB is used here only for encrypt/decrypt; production should use authenticated encryption like AES-GCM with IVs).
  - Methods: `put`, `get`, `del`, higher-level helpers `saveBlock`, `loadBlockByHash`, `loadBlockByHeight`, `saveUTXO`/`loadUTXO`, `saveState`/`loadState`, metadata helpers (`putMeta`, `getMeta`), and `saveSnapshot`.
- Risks & caveats:
  - ECB mode is insecure for many patterns; replaced in prod by AES-GCM with unique nonces/IVs.
  - No explicit database compaction or backup logic beyond LevelDB defaults. Snapshot saves are provided at pruning points.

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/Difficulty.java`**
- Purpose: Difficulty retarget logic. Compares actual time for a block interval to expected, increases or decreases difficulty by 1 depending on ratio thresholds (half or double).
- Note: Very small-step adjustments and simple policy; fine for prototype.

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/Interpreter.java`**
- Purpose: Deterministic, metered smart contract interpreter for a tiny IoT-focused VM.
- Instruction set (`OpCode`) is single-byte; `Interpreter` fetches opcodes, deducts gas (via `op.getGasCost()`), and executes a restricted set of operations.
- Notable op implementations:
  - Arithmetic stack ops (ADD, SUB, MUL, DIV), PUSH/POP.
  - Storage ops: `SLOAD`/`SSTORE` interacting with `ContractState` for a contract address in the `BlockchainContext`.
  - `SYSCALL` is used to access hardware via `HardwareManager` and enforces capability checks via `Capability`.
  - Gas metering (`deductGas`) and stack overflow checks (limit 1024) ensure resource bounds.
- Security/isolation:
  - No host file I/O, reflection, or non-deterministic APIs; VM limited to provided syscalls.
  - `handleSyscall()` includes simple rate-limiting and capability checks.
- Caveats:
  - Only a subset of opcodes implemented; many throw "not yet implemented".
  - Gas unit accounting derived from transaction fee * 1000, which must be tuned.

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/Consensus.java`**
- Small interface defining `validateBlock(Block, List<Block>)` and `selectLeader(List<String>, long)`.
- Implemented by PBFT (partial) and PoA pattern is separate (simpler PoA class — PoA does not implement this interface directly but provides sign/verify semantics).

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/Config.java`**
- Centralized configuration constants (difficulty, block size, target block time, miner reward, mempool limit, toggles like `ENABLE_SMART_CONTRACTS`) and environment helpers (`getEnv`, `getIntEnv`, `getBytesEnv`).
- `getNodePrivateKey()` requires `NODE_PRIVATE_KEY` env var — essential for P2P identity.
- Caveat: Many `public static final` values set defaults that are suitable for testing; production tuning required.

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/Utils.java`**
- Tiny helper with `safeLong(Object)` to robustly parse numeric JSON fields.

---

**`blockchain-java/src/main/java/com/hybrid/blockchain/Capability.java`**
- Represents device/contract capability (e.g., `READ_SENSOR`, `WRITE_ACTUATOR`) keyed by `Type` and `deviceId`. Implements `equals`/`hashCode` so `Set<Capability>` works for capability checks.

---

**Identity package**
- **`blockchain-java/src/main/java/com/hybrid/blockchain/identity/SSIManager.java`**
  - Self-Sovereign Identity manager for DID and Verifiable Credentials.
  - Stores DID registry (DID -> `DecentralizedIdentifier`), VC store (subject DID -> list of `VerifiableCredential`), device->DID mapping, revoked DIDs. Supports registerDID, resolveDID, transferOwnership (simplified), issueCredential, hasCredential, revokeDID, verifyDIDSignature, etc.
  - `toJSON()` returns a compact representation for snapshotting.
  - Caveats: simplified signature checks, assumes DID controller can be trusted; production would integrate DID-resolver semantics and on-chain anchoring.

- **`blockchain-java/src/main/java/com/hybrid/blockchain/identity/VerifiableCredential.java`**
  - W3C-style VC object with `CredentialSubject`, `Proof`. `sign()` uses `Crypto.sign` to create a proof and encodes signature hex in `proof.signatureValue`.
  - `serializeForSigning()` produces deterministic byte serialization for signing.
  - `isExpired()` is a stub — date parsing should be implemented for production.

- **`blockchain-java/src/main/java/com/hybrid/blockchain/identity/DecentralizedIdentifier.java`**
  - Simple DID document generator `did:iot:<deviceId>` with verification methods (publicKeyHex) and helper `verifySignature`.
  - `toDIDDocument()` returns a map representing W3C DID document structure.

---

**Privacy package**
- **`blockchain-java/src/main/java/com/hybrid/blockchain/privacy/ZKProofSystem.java`**
  - Prototype zero-knowledge constructs: RangeProof, OwnershipProof, EqualityProof, ThresholdProof.
  - Implementations are simplified (Pedersen-style commitments via hash, and signature-based simplified proofs). Clearly flagged as simplified; production requires Bulletproofs/zk-SNARKs for security.

- **`blockchain-java/src/main/java/com/hybrid/blockchain/privacy/PrivateDataManager.java`**
  - Manages `PrivateDataCollection` instances (collectionId -> `PrivateDataCollection`) and operations for creation, retrieval, deletion, and membership queries.

- **`blockchain-java/src/main/java/com/hybrid/blockchain/privacy/PrivateDataCollection.java`**
  - Confidential data store encrypted by a symmetric `collectionKey`. Stores encrypted bytes and public hashes for integrity verification.
  - Encryption: AES/ECB/PKCS5Padding with generated 256-bit key (ECB is insecure in general; use AES-GCM and key-wrapping in production). Provides `fromKey` for reconstructing a collection with an externally-provided key.

---

**Lifecycle & Device Management**
- **`blockchain-java/src/main/java/com/hybrid/blockchain/lifecycle/DeviceLifecycleManager.java`**
  - Tracks full device lifecycle states (PROVISIONING → ACTIVE → SUSPENDED → REVOKED → DECOMMISSIONED).
  - `provisionDevice()` requires manufacturer attestation; `activateDevice()` creates a DID via `SSIManager`; firmware updates tracked with `FirmwareUpdate` records; revoke/decommission flows revoke DIDs through `SSIManager`.
  - `toJSON()` serializes registry for snapshotting.
  - Caveats: authorization checks are simplified; production requires multisig/governance for critical lifecycle actions.

---

**Security primitives**
- **`blockchain-java/src/main/java/com/hybrid/blockchain/security/MultiSigManager.java`**
  - Implements M-of-N multi-signature wallet proposals with create/sign/execute workflows. Uses `Crypto.sign` and `Crypto.verify` to validate signatures.
  - `Proposal` generates deterministic signing message and stores signatures. `executeProposal` requires `hasEnoughSignatures`.
  - Good for governance flows (transfer ownership, firmware updates, emergency stop).

- **`blockchain-java/src/main/java/com/hybrid/blockchain/security/RateLimiter.java`**
  - Token-bucket per-identifier rate limiting with presets for transactions, API, connections. Includes cleanup and stats.

- **`blockchain-java/src/main/java/com/hybrid/blockchain/security/QuantumResistantCrypto.java`**
  - Experimental PQC integration (BCPQC provider) with Dilithium key generation, signing, verification, and hybrid signature mode combining ECDSA + Dilithium.
  - Includes key-derivation using SHA3-512 and helpers to serialize hybrid signatures.

---

**Contract storage & hardware**
- **`blockchain-java/src/main/java/com/hybrid/blockchain/ContractState.java`**
  - Simple persistent per-contract key-value store for 64-bit keys and values. `serializeCanonical()` sorts keys for determinism and `calculateRoot()` returns hash.
- **`blockchain-java/src/main/java/com/hybrid/blockchain/HardwareManager.java`**
  - Mocked HAL for sensors/actuators. Supports `readSensor`, `queueActuator` (deferred actions tied to block hash), `commitDeferredActions` (executed when blocks reach finality), and direct `writeActuator` for emergency operations.
  - Used by smart contract `SYSCALL` operations via `Interpreter.BlockchainContext`.

---

**Audit & Monitoring**
- **`blockchain-java/src/main/java/com/hybrid/blockchain/audit/AuditLogger.java`**
  - Append-only cryptographic audit log with chaining (each entry references previous hash), event types enumerated, export to JSON, integrity verification, and query helpers.

- **`blockchain-java/src/main/java/com/hybrid/blockchain/monitoring/BlockchainMonitor.java`**
  - Runtime metrics collectors, health checks, alerts, and dashboard snapshot helper. Collectors track counts, totals, averages and provide summaries.

---

**API layer**
- **`blockchain-java/src/main/java/com/hybrid/blockchain/api/JwtManager.java`**
  - Simple JWT manager using `io.jsonwebtoken` with HMAC SHA-256 and a static `SECRET` string (placeholder). Provides `issueToken`, `validateToken`, and `getDeviceId`.
  - Important: `SECRET` MUST be replaced in production, and tokens should be bound carefully to identities and scopes.

- **`blockchain-java/src/main/java/com/hybrid/blockchain/api/IoTDeviceManager.java`**
  - Simple in-memory device registry storing public keys.

- **`blockchain-java/src/main/java/com/hybrid/blockchain/api/IoTRestAPI.java`**
  - Spring Boot REST API exposing account creation, account query, transaction submission, tx lookup, mempool query, block queries, network/peers endpoints, contract stubs.
  - `init()` demonstrates wiring: constructs `Mempool`, `PoAConsensus`, `Blockchain` and initializes state. Uses read/write locks (`blockchainLock`) to protect access.
  - `createAccount()` demonstrates key generation using BouncyCastle and returns an address, public key, private key hex, and token (private key must be handled securely!).
  - `submitTransaction()` shows building a `Transaction` from request, incrementing nonce, adding to blockchain mempool.
  - Important security notes: Authorization uses JWT tokens issued by `JwtManager` with a static secret — replace and harden in production.

---

**VM and instruction set**
- **`blockchain-java/src/main/java/com/hybrid/blockchain/OpCode.java`**
  - Enumerates VM opcodes and associated gas costs. Helps decouple VM op definitions from `Interpreter`.

---

**Network & P2P**
- **`blockchain-java/src/main/java/com/hybrid/blockchain/PeerNode.java`**
  - Simplified P2P node implementing a handshake (HELLO, CHALLENGE, HANDSHAKE_OK) with signature-based mutual identity verification using `NODE_PRIVATE_KEY` and `Crypto.sign`/`verify`.
  - Secure session loop reads typed messages with seq numbers and payload lengths. `processMessage` stub left to implement core logic (tx/block broadcast, peer discovery).
  - Important: Network protocol uses custom framing and expects compressed pubkey length 33; error handling often swallows exceptions — consider logging and backoff strategies.

---

**Small utilities & tests**
- **`blockchain-java/src/main/java/com/hybrid/blockchain/HexUtils.java`**: thin wrapper over BouncyCastle Hex encoder/decoder with safe defaults.
- **`blockchain-java/src/main/java/com/hybrid/blockchain/IdentityManager.java`**: holds a mapping of node ids to `PublicKey` objects for authorization checks in some components.
- **`blockchain-java/src/main/java/com/hybrid/blockchain/TestBlockchain.java`**: example/test main that sets up a `PrunedBlockchain`, generates validators, signs blocks, and exercises pruning. Useful as a runnable integration smoke test.
- **`blockchain-java/src/main/java/com/hybrid/App.java`**: trivial maven archetype `Hello World` class.

---

## Cross-file architecture summary & important interactions
- Persistence: `Storage` is the canonical durable layer. `Blockchain` writes blocks, UTXO set, and account state there. `PrunedBlockchain` may remove older blocks and save snapshots.
- Consensus & Signing: `PoAConsensus` signs/validates block signatures. `PBFTConsensus` is a separate interface-based implementation that provides a vote/commit workflow for private networks.
- Transaction models: Hybrid design supports both UTXO and account models. `Transaction.Type` drives validation and application logic inside `Blockchain.applyBlock`.
- Smart contracts: Contracts are represented by `Transaction.Type.CONTRACT` payloads containing VM bytecode executed by `Interpreter`. Contracts have isolated `ContractState`, capabilities, and access to `HardwareManager` through `Interpreter.BlockchainContext` for safe syscalls.
- Hardware integration: `HardwareManager` queues physical actuator changes (deferred until blocks are finalized); this avoids executing real-world actions until sufficient confirmations.
- Identity and privacy:
  - SSI (DID + Verifiable Credentials) handled by `SSIManager`, `DecentralizedIdentifier`, `VerifiableCredential`.
  - Private collections handled by `PrivateDataCollection` and `PrivateDataManager` with encryption and public hashes for integrity.
- Security & resilience:
  - Crypto primitives centralize ECDSA (secp256k1) operations in `Crypto`. `QuantumResistantCrypto` offers a migration path.
  - Rate limiting (`RateLimiter`) and multi-sig (`MultiSigManager`) provide anti-abuse and governance tools.
- Observability: `AuditLogger` provides tamper-evident logs and `BlockchainMonitor` collects runtime metrics and alerts.

---

## Recommendations & TODOs (codebase hygiene & production hardening)
- Replace AES/ECB with AES-GCM with unique IVs and authentication tags in `Storage` and `PrivateDataCollection`.
- Move secrets out of source: `Config.STORAGE_AES_KEY` and `JwtManager.SECRET` must be provided via secure env/config management and not embedded defaults.
- Improve mempool and UTXO performance: index UTXOs by address, and consider persistent mempool or snapshot mempool on graceful shutdown.
- Harden networking: implement robust `processMessage` handlers, add TLS or authenticated transport, and improve error logging.
- Expand VM: implement remaining opcodes, add formal verification or fuzz testing, and integrate deterministic gas accounting.
- Formal tests: expand unit tests beyond existing `src/test` artifacts and add integration tests for P2P, snapshot/restore, and reorg handling.

---

If you want, I can now:
- 1) Commit this `DETAILED_JAVA_EXPLANATION.md` to the repo (already created),
- 2) Produce a shorter `README.md` summary highlighting how to run the Java tests and the `TestBlockchain` runner,
- 3) Generate a per-file table-of-contents with direct line-number links to key functions.

Which next step do you want me to take?