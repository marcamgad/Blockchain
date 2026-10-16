# HybridChain Security Audit

| | |
|---|---|
| **Target** | `com.hybrid.blockchain` (HybridChain / X-Ledger), Java 17, Maven |
| **Revision** | working tree at 2026-10-09 (git repo un-inspectable: `dubious ownership`, see §0.3) |
| **Artifact tested** | `target/blockchain-java-1.0-SNAPSHOT-jar-with-dependencies.jar` (49,058,680 B, `Main-Class: com.hybrid.App`) |
| **Batch** | **1 of N** — inventory, threat model, and the transport / P2P / consensus-quorum / IoT-ingress / deployment surface |
| **PoC suite** | `src/test/java/com/hybrid/blockchain/audit/HybridChainAuditPoCTest.java` |

> Run every PoC: `mvn -o test -Dtest=HybridChainAuditPoCTest -Daudit.excluded=none`
> Each PoC asserts the **correct** behaviour, so it **fails while the bug exists** and becomes
> the regression test once fixed. They are tagged `audit-poc` and excluded from the default
> build (`pom.xml` property `audit.excluded`) so the main suite stays green.

---

## 0. Method and evidence rules

### 0.1 Status labels
- **CONFIRMED** — a PoC was written **and executed**; output is quoted.
- **LIKELY** — the code path is cited line-by-line and clearly reachable; no executed PoC yet.
- **SUSPECTED** — needs information I do not have.

### 0.2 What was actually executed for this batch
1. Built-artifact inspection (manifest, class presence, signature files).
2. **Live start of the shipped jar** with a 4-validator configuration (log quoted in HC-009).
3. 8 JUnit PoCs — **all 8 fail as predicted** (output quoted per finding).
4. One standalone PoC against the shipped jar with production defaults (HC-001).

### 0.3 Not auditable in this environment
- **Git history** — `git` refuses to run (`detected dubious ownership in repository at 'D:/Blockchain'`, owner `BUILTIN/Administrators`). Commit provenance, blame and supply-chain-of-commits review were **not** performed.
- **Live REST API** — the embedded Tomcat cannot start in this sandbox. Proven environmental: a 10-line Java program containing no project code fails identically (`Selector.open(): FAILED -> java.io.IOException: Unable to establish loopback connection`; JDK 17 on Windows uses an AF_UNIX wake-up pipe that the sandbox blocks). REST endpoints are therefore reviewed statically plus via the existing MockMvc tests only.
- **Dependency CVE scan** — OWASP Dependency-Check needs an NVD download; not run in this batch.
- **Real Raspberry Pi hardware** — none available.

### 0.4 Corrections made during this batch (recorded, not hidden)
- My first HC-003 PoC asserted that an attacker's forged certificate verifies against a victim CA. It **passed** (i.e. did not verify). Investigation showed the hypothesis was wrong only because of a *worse* bug (HC-005). The PoC was rewritten to test the directly provable property.
- My own earlier deliverables contained errors this audit exposed: `EDGE_DEPLOYMENT.md` (5 errors, corrected 2026-10-09), `PRODUCTION_CHECKLIST.md` (port 8000 — corrected), and **HC-004 is a defect I introduced** when consolidating the IoT adapters (copied `Config.NODE_ID` as the signer identity).

---

## STEP 1 — Inventory

94 main files, 19,935 LOC; 132 test files, 16,778 LOC. Responsibility lines below are taken from each class's own Javadoc where present (verified by extraction), otherwise from reading the class.

### 1.1 Entry points & wiring
| File | LOC | Responsibility |
|---|---:|---|
| `com/hybrid/App.java` | 269 | **Production entry point** (jar `Main-Class`). Reads keys/env, builds CA, PBFT, Storage, Blockchain, PeerNode (CA mTLS path), proposer loop, Spring API. |
| `blockchain/App.java` | 179 | **Second entry point** with a different wiring: uses the legacy trust-all TLS `PeerNode` ctor (HC-006). |
| `blockchain/Config.java` | 194 | Static configuration from env/system properties; several values are mutable statics. |
| `blockchain/tools/KeygenTool.java` | 56 | Generates N secp256k1 keys deterministically from a seed (default seed HC-010). |

### 1.2 Consensus
| File | LOC | Responsibility |
|---|---:|---|
| `consensus/PBFTConsensus.java` | 785 | PBFT: votes, quorum, view change, timer, reputation-weighted + ZK-gated leader selection. |
| `PoAConsensus.java` | 224 | Round-robin PoA with equivocation slashing (alternative engine). |
| `Consensus.java` | 16 | Engine interface. |
| `Validator.java` | 19 | (id, pubkey) value object. |
| `Checkpoint.java` | 80 | Finalised checkpoint for fast sync. |

### 1.3 Ledger & state
| File | LOC | Responsibility |
|---|---:|---|
| `Blockchain.java` | 1671 | Chain state machine: tx validation, block application (with S1-01 rollback), fork handling, block creation. |
| `Block.java` | 191 | Block header/body, canonical serialisation, PoW `mine()` (vestigial). |
| `Transaction.java` | 430 | Tx model, canonical serialisation, ECDSA + optional Dilithium verification. |
| `AccountState.java` | 726 | Balances, nonces, multi-token, code/storage, commitments/nullifiers. |
| `UTXOSet.java`, `UTXOInput/Output.java` | 161+21+21 | UTXO model. |
| `Mempool.java` | 355 | RW-locked TreeMap pool with replacement/eviction. |
| `FeeMarket.java` | 206 | EIP-1559-style base fee. |
| `Tokenomics.java` | 99 | Reward schedule / supply cap. |
| `TokenRegistry.java` | 262 | Multi-token registry. |
| `MerkleTree.java`, `MerklePatriciaTrie.java` | 42+633 | Tx root; MPT. |
| `Difficulty.java` | 31 | PoW difficulty adjustment (vestigial under PBFT). |
| `PrunedBlockchain.java` | 51 | Blockchain variant with pruning. |
| `Storage.java` | 564 | LevelDB (JNI with pure-Java fallback), AES-GCM at rest. |
| `TransactionReceipt.java`, `ExecutionResult.java`, `RevertException.java`, `DeferredAction.java`, `BlockValidationException.java` | — | Execution artefacts. |

### 1.4 Crypto & identity
| File | LOC | Responsibility |
|---|---:|---|
| `Crypto.java` | 137 | SHA-256, secp256k1 ECDSA (RFC6979, low-S), address derivation. |
| `security/QuantumResistantCrypto.java` | 225 | Dilithium (BC PQC) + hybrid signature. |
| `security/CertificateAuthority.java` | 172 | mTLS CA derived from a seed (HC-003, HC-005). |
| `security/SSLUtils.java` | 140 | SSLContext builders (CA path; legacy trust-all path HC-006). |
| `security/HardwareSecurityModule.java` | 66 | **Simulated** HSM. |
| `security/PUFIdentityProvider.java`, `security/PufAnchorRegistry.java` | 83+81 | Simulated PUF key derivation; on-chain anchor registry. |
| `security/MultiSigManager.java` | 455 | Multisig wallets. |
| `identity/DecentralizedIdentifier.java`, `identity/SSIManager.java`, `identity/VerifiableCredential.java` | 240+330+265 | W3C DID / VC for devices. |
| `security/IdentityUtils.java`, `IdentityManager.java` | 47+25 | DID helpers. |
| `privacy/ZKProofSystem.java` | 794 | Hand-written Schnorr/Pedersen proofs over secp256k1. |
| `privacy/PrivateDataCollection.java`, `privacy/PrivateDataManager.java` | 301+194 | Private data collections. |
| `reputation/ReputationEngine.java`, `reputation/ZkEligibilityGate.java` | 143+61 | Device reputation; ZK reputation threshold proof. |

### 1.5 Networking
| File | LOC | Responsibility |
|---|---:|---|
| `PeerNode.java` | 1000 | TCP+TLS P2P server/client, app-layer handshake, PBFT message routing, sync. |
| `p2p/GossipEngine.java` | 124 | Duplicate suppression, fan-out relay. |
| `p2p/PeerManager.java` | 174 | Peer table, scoring, bans, **eclipse threshold (HC-001)**. |
| `p2p/P2PMessage.java` | 99 | Signed message envelope. |

### 1.6 API / ingress
| File | LOC | Responsibility |
|---|---:|---|
| `api/IoTRestAPI.java` | 1125 | Spring REST controller (public reads, JWT writes, admin). |
| `api/JwtManager.java`, `security/JwtAuthFilter.java`, `security/SecurityConfig.java` | 231+67+103 | JWT issue/verify, filter, Spring Security rules. |
| `security/RateLimiter.java` | 205 | Token-bucket limiter. |
| `api/AbstractIoTMessageAdapter.java`, `api/IoTMessageAdapter.java` | 107+16 | Shared IoT ingress (rate-limit/validate/sign/submit) — **HC-004, HC-013**. |
| `api/MQTTAdapter.java`, `api/CoAPAdapter.java` | 102+180 | Wired MQTT/CoAP adapters. |
| `iot/MQTTAdapter.java`, `iot/CoAPAdapter.java` | 108+126 | **Unwired** batching adapters (`TELEMETRY_BATCH`). |
| `api/EventBus.java`, `api/EventBusWebSocketHandler.java`, `api/WebSocketConfig.java` | 189+73+23 | WebSocket event stream. |
| `api/MDCCorrelationInterceptor.java`, `api/IoTDeviceManager.java` | 36+25 | Correlation IDs; device helper. |

### 1.7 Contracts, IoT, AI, ops
| File | LOC | Responsibility |
|---|---:|---|
| `Interpreter.java`, `OpCode.java` | 468+92 | Custom metered bytecode VM. |
| `WasmContractEngine.java` | 188 | Chicory WASM runtime with host functions and gas. |
| `ABIEncoder/Decoder.java`, `ContractABI/Event/State.java`, `Capability.java` | — | Contract ABI and capabilities. |
| `HardwareManager.java` | 95 | IoT HAL; deferred actuator commits after finality. |
| `iot/IoTStateChannel.java` | 61 | Off-chain telemetry channel. |
| `lifecycle/DeviceLifecycleManager.java` | 793 | Device registry, firmware history, reputation updates. |
| `ai/*` (6 files) | 1217 | FL aggregation (robust strategies), DP, threat scorer, anomaly detector, contract auditor, ProofOfInference. |
| `governance/GovernanceManager.java` | 65 | Governance council. |
| `anchoring/StateAnchoringManager.java` | 55 | External-chain anchoring. |
| `audit/AuditLogger.java`, `monitoring/*` | 328+699 | Audit log; metrics; Prometheus bridge. |
| `examples/MPTUsageExample.java`, `Utils.java`, `HexUtils.java` | — | Example / utilities. |

### 1.8 Data flow and trust boundaries

```
                    ┌──────────────── untrusted ────────────────┐
 IoT device ──CoAP/UDP 5683──► api/CoAPAdapter ─┐                │
 IoT device ──MQTT (broker)──► api/MQTTAdapter ─┼─► AbstractIoTMessageAdapter.submitDeviceTx
                                                │     (signs AS THE NODE — HC-004/HC-013)
 Client ──HTTP 8080──► JwtAuthFilter ► IoTRestAPI ─┼─► Blockchain.addTransaction ► Mempool
                                                │
 Any host ──TCP 6001──► TLS (CA path HC-005 / trust-all HC-006)
                         ► app handshake (key possession only, no authorisation HC-011;
                           unbounded pre-auth allocation HC-007; no timeouts HC-012)
                         ► GossipEngine ► {TRANSACTION, BLOCK, CONSENSUS} handlers
                                   ▲              ► PBFTConsensus votes ► quorum (HC-002)
                                   └─ outbound: selectGossipPeers (HC-001)
                    └────────────────────────────────────────────┘
 Trusted (per node): env file (NODE_PRIVATE_KEY, STORAGE_AES_KEY→CA HC-003, JWT_SECRET,
                     VALIDATOR_PUBKEYS, REQUIRE_QUANTUM_SIG HC-008), local LevelDB.
```

| Boundary | Who can cross | Authentication today |
|---|---|---|
| CoAP 5683/UDP | anyone with network reach | **none** (rate limit only) |
| MQTT topics | anyone the broker admits | **none in the node**; depends on broker |
| REST 8080 | anyone | GET public; POST JWT; admin JWT+role |
| P2P 6001 | anyone | TLS (broken/trust-all) + key-possession handshake; **no authorisation** |
| Consensus votes | validators | ECDSA per vote, validator-set membership checked |
| Env file | operator | filesystem permissions |

---

## STEP 2 — Threat model

### 2.1 Attackers
| Attacker | Capability assumed |
|---|---|
| A1 Network adversary | Reorders/delays/drops packets before GST; opens arbitrary TCP/UDP connections. |
| A2 Byzantine validator | Holds ≤ f validator keys; equivocates; sends arbitrary consensus messages. |
| A3 Malicious peer (non-validator) | Any key pair; connects to P2P port. |
| A4 Malicious device / client | Reaches CoAP/MQTT/REST. |
| A5 Insider / operator | Reads one node's env file or disk. |
| A6 Supply chain | Malicious dependency or build artefact. |

### 2.2 Assets
Ledger safety (one block per height), liveness, validator keys, CA key, at-rest data, device identities/reputation, IoT data integrity, API admin capability.

### 2.3 Assumptions — verified against code

| # | Assumption | Enforced? | Evidence |
|---|---|---|---|
| A-1 | n ≥ 3f+1 at construction | **Partially** | `PBFTConsensus.java:147-151` requires n ≥ 4, sets `f=(n-1)/3`. `addValidator/removeValidator` can change n at runtime; `f` is `final` and never recomputed. |
| A-2 | Any two quorums intersect in an honest node | **NO for n ≠ 3f+1** | Quorum fixed at `2f+1` (`:655,:697,:737`). **HC-002 CONFIRMED.** |
| A-3 | A commit certificate is for one value | **Yes (fixed S7-01)** | `getQuorumHash` groups by hash. |
| A-4 | Honest nodes compute the same leader | **Yes for reputation (fixed E1–E3)**; | Theorem 1/2 in `docs/formal/pbft_leader_model.md`. |
| A-5 | Messages propagate between honest nodes after GST | **NO at n=4** | **HC-001 CONFIRMED.** |
| A-6 | Peers are authenticated at the transport layer | **NO** | CA path never verifies (**HC-005**); legacy path trusts all (**HC-006**). |
| A-7 | Only authorised nodes may peer | **NO** | Handshake checks key possession only (**HC-011**). |
| A-8 | All honest nodes apply identical validity rules | **NO** | `REQUIRE_QUANTUM_SIG` is per-node env (**HC-008**). |
| A-9 | Disk key compromise of one node is contained | **NO** | CA key = SHA-256(`STORAGE_AES_KEY`), key must be network-wide (**HC-003**). |
| A-10 | Device data is authenticated to the device | **NO** | Ingress signs with the gateway's key, deviceId taken from payload (**HC-013**). |

---

## PART 1 — Executive summary (interim, batch 1)

**Overall risk: CRITICAL. Not deployable as a multi-node network.**

The shipped jar starts a single node correctly through genesis and P2P listener start (observed). But **no network built from it can function**: nodes cannot complete TLS with each other (HC-005), a minimum-size network cannot send any message (HC-001), any network of 5 or 6 validators can finalise two different blocks at one height (HC-002), and IoT telemetry — the system's purpose — is always rejected (HC-004).

| Severity | Count (batch 1) |
|---|---:|
| CRITICAL | 3 |
| HIGH | 7 |
| MEDIUM | 3 |
| LOW | 1 |
| INFO | 1 |

**Top 5 most dangerous**
1. **HC-002** — Quorum `2f+1` is unsafe for n ∉ {3f+1}: two honest replicas certify different blocks at one height (CONFIRMED).
2. **HC-005** — Production CA pairs a deterministic private key with a *random* public key: no certificate ever verifies; nodes cannot connect (CONFIRMED end-to-end).
3. **HC-001** — Eclipse threshold 4 > peers available (3) at n = 4: no message ever leaves a node (CONFIRMED on the shipped jar).
4. **HC-003** — CA signing key is `SHA-256(STORAGE_AES_KEY)`; fixing HC-005 naively makes forged node certificates instantly valid (CONFIRMED key equality).
5. **HC-007** — One unauthenticated packet makes a node attempt a ~2 GB allocation; an `OutOfMemoryError` escapes the handler's `catch (Exception)` (CONFIRMED).

**Can this go to production?** **No.** Stop-ship blockers: HC-001, HC-002, HC-003, HC-005, HC-007 — plus HC-004 if IoT ingestion is in scope. A full verdict waits for the remaining batches (§Remaining scope).

---

## PART 2 — Findings table (batch 1)

| ID | Severity | Status | Module | File:Line | Title |
|---|---|---|---|---|---|
| HC-001 | CRITICAL | CONFIRMED | P2P | `p2p/PeerManager.java:75,161` | Eclipse threshold (4) exceeds peers available in a 4-validator network → no gossip, chain halts |
| HC-002 | CRITICAL | CONFIRMED | Consensus | `consensus/PBFTConsensus.java:151,655,697,737` | Quorum `2f+1` unsafe when n ≠ 3f+1 → conflicting commits |
| HC-005 | CRITICAL | CONFIRMED | Crypto/TLS | `security/CertificateAuthority.java:73-87` | CA public key unrelated to CA private key → production mTLS never succeeds |
| HC-003 | HIGH | CONFIRMED | Crypto/TLS | `security/CertificateAuthority.java:62-71`, `App.java:58-59` | CA signing key derived from disk-encryption key; key must be network-wide |
| HC-004 | HIGH | CONFIRMED | IoT ingress | `api/AbstractIoTMessageAdapter.java:85` | Ingress tx `from` ≠ signer address → every MQTT/CoAP telemetry tx rejected |
| HC-006 | HIGH | CONFIRMED | TLS | `security/SSLUtils.java:100-112` | Legacy SSL path installs trust-all TrustManager |
| HC-007 | HIGH | CONFIRMED | P2P | `PeerNode.java:630-634, 659-663` | Pre-auth handshake allocates attacker-chosen buffer (OOM) |
| HC-008 | HIGH | LIKELY | Ledger | `Config.java:40`, `Transaction.java:324`, `Blockchain.java:228` | Per-node env changes tx validity → chain split |
| HC-012 | HIGH | LIKELY | P2P | `PeerNode.java:89,141,545-600` | No socket timeouts + unbounded threads + half-open not counted → slowloris |
| HC-013 | HIGH | LIKELY | IoT ingress | `api/CoAPAdapter.java` TelemetryResource; `AbstractIoTMessageAdapter.java:77-105` | Unauthenticated telemetry attributed to any deviceId → reputation poisoning |
| HC-009 | MEDIUM | CONFIRMED | Ops | `App.java:147`, `Config.java:60` | `API_PORT` ignored (Spring uses 8080); API failure leaves a zombie node |
| HC-010 | MEDIUM | CONFIRMED | Key mgmt | `tools/KeygenTool.java:28` | Default seed yields publicly computable validator keys |
| HC-011 | MEDIUM | LIKELY | P2P | `PeerNode.java:617-676` | Handshake authenticates key possession but never authorisation → Sybil/eclipse |
| HC-014 | LOW | LIKELY | Wiring | `App.java:231` | `getCurrentLeader().equals(...)` NPE each tick when ZK gate yields no leader |
| HC-015 | INFO | CONFIRMED | Tests | `pom.xml:287` (argLine), `MTLSSecureNetworkTest.java:44,48` | Test config masks HC-001, HC-005, HC-006 |

---

## PART 3 — Detailed findings (batch 1)

### HC-001 — Eclipse threshold exceeds available peers; a 4-validator network never gossips
**Severity:** CRITICAL (liveness) · **Status:** CONFIRMED · **Likelihood × Impact:** High × High

**Location.** `p2p/PeerManager.java:75` (`MIN_PEERS = Integer.parseInt(System.getProperty("MIN_PEERS", "4"))`), `:160-164` (`selectGossipPeers` returns `Collections.emptyList()` when `peers.size() < MIN_PEERS`). Every outbound message routes through it: `p2p/GossipEngine.java:79`; `PeerNode.java:500, 781, 830, 845, 878, 886`.

**Description.** PBFT requires n ≥ 4 (`PBFTConsensus.java:147`). In an n-validator network each node has at most n − 1 peers. At the minimum n = 4 a node has 3 peers, `3 < 4`, so gossip selection is always empty: no PREPARE, COMMIT, block or transaction ever leaves any node. `MIN_PEERS` is read only as a JVM system property; setting it in an env file has no effect. The only override in the repository is the test JVM (`pom.xml:287`, `-DMIN_PEERS=1`), so the entire suite runs a configuration production never gets.

**Scenario.** Operator deploys the documented minimum of 4 validators → every node logs `Eclipse protection: waiting for at least 4 peers (current: 3)` forever → height stays 0.

**PoC (executed against the shipped jar, production defaults).**
```
MIN_PEERS (as shipped, no -D override) = 4
N=4 validators -> 3 peers -> gossip targets = 0   <-- NOTHING IS EVER SENT
N=5 validators -> 4 peers -> gossip targets = 3
N=6 validators -> 5 peers -> gossip targets = 3
```
JUnit: `HybridChainAuditPoCTest.hc001_fourValidatorNetworkCanGossip` (forks a fresh JVM without `-DMIN_PEERS`) → fails: `selectGossipPeers returned 0 targets`.

**Fix.** Derive the threshold from the actual network size; keep 4 only as an upper bound.

`p2p/PeerManager.java`:
```java
    public static final int MIN_PEERS = Integer.parseInt(
            System.getProperty("MIN_PEERS", System.getenv().getOrDefault("MIN_PEERS", "4")));

    /** Effective eclipse threshold; never larger than the number of peers that can exist. */
    private volatile int minPeers = MIN_PEERS;

    public void setMinPeers(int n) {
        this.minPeers = Math.max(1, n);
    }

    public int getMinPeers() {
        return minPeers;
    }

    public List<PeerInfo> selectGossipPeers(int fanout, String excludeId) {
        if (peers.size() < minPeers) {
            log.warn("[PeerManager] Eclipse protection: waiting for at least {} peers (current: {})", minPeers, peers.size());
            return Collections.emptyList();
        }
        List<PeerInfo> eligible = new ArrayList<>();
        for (PeerInfo p : peers.values()) {
            if (!p.getId().equals(excludeId) && p.getScore() >= 30) {
                eligible.add(p);
            }
        }
        Collections.shuffle(eligible);
        return eligible.subList(0, Math.min(fanout, eligible.size()));
    }
```
`PeerNode.java`, in **both** constructors, immediately after `this.peerManager = new PeerManager();`:
```java
        int possiblePeers = (consensus != null && consensus.getValidators() != null)
                ? consensus.getValidators().size() - 1
                : PeerManager.MIN_PEERS;
        this.peerManager.setMinPeers(Math.min(PeerManager.MIN_PEERS, Math.max(1, possiblePeers)));
```
**Regression test.** `hc001_fourValidatorNetworkCanGossip` (must pass); additionally remove `-DMIN_PEERS=1` from `pom.xml:287` so the suite exercises the production default.

---

### HC-002 — Quorum `2f+1` is unsafe when n ≠ 3f+1
**Severity:** CRITICAL (safety — two blocks finalise at one height) · **Status:** CONFIRMED · **Likelihood × Impact:** Medium × Critical
**Consensus-rule change — requires coordinated upgrade.**

**Location.** `consensus/PBFTConsensus.java:151` (`f = (n-1)/3`, `final`), `:655` (`needed = 2*f+1` for PREPARE/COMMIT), `:697` and `:737` (view-change quorum `2f+1`).

**Description.** Safety requires every two quorums of size q to share at least f + 1 replicas, i.e. `2q − n ≥ f + 1` ⇒ `q ≥ ⌈(n+f+1)/2⌉`. `2f+1` meets this only when n = 3f + 1. For n = 5 (f = 1, q = 3) two quorums can overlap in one node — the Byzantine one. For n = 6 (f = 1) likewise. Independently, `f` is fixed at construction while `addValidator/removeValidator` change n at runtime, so the quorum drifts further from correct after any validator-set change.

**Scenario (n = 5).** Byzantine primary B pre-prepares block A to {h1, h2} and block B to {h3, h4}. h1, h2 commit A with votes {B, h1, h2}; h3, h4 commit B with {B, h3, h4}. Neither honest side ever sees both of B's votes, so no equivocation is detected. Two honest replicas have finalised different blocks at the same sequence.

**PoC (executed).** `hc002_quorumUnsafeForNNotEqual3fPlus1`:
```
[HC-002] n=5 f=1 quorum=3: h1 certifies A, h3 certifies B
HC-002 SAFETY: two honest replicas hold commit certificates for different blocks (A vs B) at seq 7
```

**Fix.** Compute the intersection-safe quorum from the *current* validator set.

`consensus/PBFTConsensus.java` — add:
```java
    /** Largest f tolerated by the current validator set. */
    private int currentF() {
        return (validators.size() - 1) / 3;
    }

    /**
     * Byzantine quorum size ⌈(n+f+1)/2⌉. Guarantees any two quorums intersect in at least
     * f+1 replicas, hence in at least one correct replica. Equals 2f+1 when n = 3f+1.
     */
    private int quorumSize() {
        int n = validators.size();
        return (n + currentF()) / 2 + 1;
    }
```
and replace the three thresholds:
```java
        int needed = quorumSize();                                   // was: 2 * f + 1   (line 655)
        if (viewChangeLog.get(newView).size() >= quorumSize())       // was: (2 * f + 1) (line 697)
        if (viewChangeLog.get(nextView).size() >= quorumSize()) {    // was: (2 * f + 1) (line 737)
```
Also replace remaining uses of the `final int f` with `currentF()` and remove the field.

**Regression test.** `hc002_quorumUnsafeForNNotEqual3fPlus1` (must pass); add a parameterised test over n ∈ {4..10} asserting `2·quorumSize() − n ≥ currentF() + 1`.

---

### HC-005 — The CA's public key does not belong to its private key; production mTLS never succeeds
**Severity:** CRITICAL (liveness — network cannot form) · **Status:** CONFIRMED · **Likelihood × Impact:** Certain × High

**Location.** `security/CertificateAuthority.java:73-87`:
```java
KeyPair generatedPair = keyGen.generateKeyPair();               // random key pair
...
PrivateKey prvKey = keyFactory.generatePrivate(privateKeySpec);  // derived from seed
PublicKey  pubKey = generatedPair.getPublic();                   // from the RANDOM pair
return new KeyPair(pubKey, prvKey);
```
**Description.** Certificates are signed with the seed-derived private key but every CA certificate embeds the public key of an unrelated random key pair. No signature made by the CA verifies under any CA certificate — not even the CA's own self-signature. `SSLUtils.createSSLContextWithCA` trusts only that CA certificate, so the production path (`com.hybrid.App` → 6-argument `PeerNode` constructor) can never complete a handshake with anyone.

**PoC (executed).**
```
[HC-005] node cert vs its own CA certificate: FAILS (SignatureException)
[HC-005] CA-path mTLS handshake: client=FAIL: PKIX path validation failed:
         java.security.cert.CertPathValidatorException: signature check failed
         | server=FAIL: Received fatal alert: bad_certificate
```
Tests: `hc005_caIssuedCertDoesNotVerifyAgainstOwnCa`, `hc005_productionMtlsHandshakeFails`.

**Why tests never caught it.** `MTLSSecureNetworkTest.java:44,48` uses the legacy 4-argument constructor, which installs a trust-all manager (HC-006). The production path has no end-to-end test (HC-015).

**Fix.** Must be applied **together with HC-003** — fixing the public key alone makes HC-003 exploitable immediately. Derive the public key from the private key:

`security/CertificateAuthority.java` — replace `generateDeterministicKeyPair`:
```java
    private KeyPair generateDeterministicKeyPair(byte[] seed) throws Exception {
        if (seed == null || seed.length < 32) {
            throw new IllegalArgumentException("CA seed must be at least 32 bytes of dedicated key material");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("HYBRIDCHAIN-NETWORK-CA-V1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] derivedKey = digest.digest(seed);

        ECNamedCurveParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec(EC_CURVE_NAME);
        BigInteger n = ecSpec.getN();
        BigInteger d = new BigInteger(1, derivedKey).mod(n.subtract(BigInteger.ONE)).add(BigInteger.ONE);

        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM, "BC");
        PrivateKey prvKey = keyFactory.generatePrivate(
                new org.bouncycastle.jce.spec.ECPrivateKeySpec(d, ecSpec));
        org.bouncycastle.math.ec.ECPoint q = ecSpec.getG().multiply(d).normalize();
        PublicKey pubKey = keyFactory.generatePublic(
                new org.bouncycastle.jce.spec.ECPublicKeySpec(q, ecSpec));
        return new KeyPair(pubKey, prvKey);
    }
```
(`d ∈ [1, n−1]` also removes the existing `mod n` path that can yield 0.)

**Regression test.** Both HC-005 PoCs must pass; add a CA-path variant of `MTLSSecureNetworkTest` that starts two production-wired `PeerNode`s.

---

### HC-003 — CA signing key is derived from the disk-encryption key, which must therefore be network-wide
**Severity:** HIGH (key compromise) · **Status:** CONFIRMED · **Likelihood × Impact:** Medium × High

**Location.** `App.java:58-59` (`new CertificateAuthority(Config.STORAGE_AES_KEY)`), `security/CertificateAuthority.java:62-71`, `SSLUtils.java:50-55` (trust store = that CA only).

**Description.** Two keys with different purposes are fused. Because each node trusts only the CA derived from *its own* `STORAGE_AES_KEY`, every node must share one value — so the per-node at-rest encryption key becomes a network secret, and anyone holding it (any operator, any leaked env file, any stolen SD card of a node with env on disk) can (a) decrypt every node's LevelDB, and (b) reconstruct the CA private key and mint node certificates for arbitrary identities. Today (b) is masked by HC-005; once HC-005 is fixed it is live.

**Scenario.** An attacker obtains one Pi's `hybridchain.env`. They derive the CA key, mint a certificate for any node ID, and are accepted at the TLS layer by every node; they also decrypt every node's chain database copied from disk.

**PoC (executed).** `hc003_caSigningKeyDerivedFromStorageKey` → `anyone holding STORAGE_AES_KEY reconstructs the network CA's private signing key bit-for-bit`.

**Fix.** Separate the secrets now; move to an offline CA as hardening (Phase 1).

`com/hybrid/App.java` — replace lines 57-61:
```java
        // 4. Initialize Certificate Authority for mTLS from DEDICATED key material.
        //    Never derive it from STORAGE_AES_KEY (HC-003): that key must stay per-node.
        String caSeedHex = System.getenv("NETWORK_CA_SEED");
        if (caSeedHex == null || caSeedHex.length() < 64) {
            throw new IllegalStateException(
                    "NETWORK_CA_SEED (>= 32 bytes hex) must be set; it must differ from STORAGE_AES_KEY");
        }
        byte[] caSeed = HexUtils.decode(caSeedHex.trim());
        if (java.util.Arrays.equals(caSeed, Config.STORAGE_AES_KEY)) {
            throw new IllegalStateException("NETWORK_CA_SEED must not equal STORAGE_AES_KEY");
        }
        CertificateAuthority ca = new CertificateAuthority(caSeed);
        X509Certificate caCert = ca.getCACertificate();
        log.info("[mTLS] CA initialized with root certificate CN={}", caCert.getSubjectX500Principal().getName());
```
Interim residual risk (document it): every node still holds CA signing material. Target design: CA key held offline; nodes receive `node.p12` + `ca.crt` only.

**Regression test.** `hc003_caSigningKeyDerivedFromStorageKey` rewritten to construct the CA from `NETWORK_CA_SEED` and assert two different `STORAGE_AES_KEY` values on two nodes still interoperate.

---

### HC-004 — IoT ingress signs as `Config.NODE_ID`, which is not the node's address; every telemetry tx is rejected
**Severity:** HIGH (complete loss of the system's primary ingestion path) · **Status:** CONFIRMED · **Likelihood × Impact:** Certain × High
**Introduced by the auditor's own earlier adapter consolidation.**

**Location.** `api/AbstractIoTMessageAdapter.java:85` (`String nodeAddr = Config.NODE_ID;`), signed at `:97-101` with `Config.getNodePrivateKey()`. `Config.NODE_ID` defaults to `"node-" + currentTimeMillis()` (`Config.java:74`) and `App.java` never assigns it (grep: no assignment anywhere). `Transaction.verify` requires `Crypto.deriveAddress(pubKey).equals(from)` (`Transaction.java:316`); `Blockchain.validateTransaction:228` rejects otherwise.

**PoC (executed).** `hc004_iotIngressTransactionsAreUnsignable`:
```
[HC-004] ingress tx from=node-1791559566957 verify()=false
```

**Fix.** Sign as the address of the key actually used.

`api/AbstractIoTMessageAdapter.java` — replace the body of `submitDeviceTx` from `String nodeAddr = Config.NODE_ID;` through `blockchain.addTransaction(tx);`:
```java
        BigInteger privKey = null;
        try {
            privKey = Config.getNodePrivateKey();
        } catch (RuntimeException noKey) {
            // debug/test only: no node key configured
        }
        byte[] pubKey = privKey != null ? Crypto.derivePublicKey(privKey) : null;
        String nodeAddr = pubKey != null ? Crypto.deriveAddress(pubKey) : Config.NODE_ID;

        long nonce = blockchain.getNonce(nodeAddr) + 1;
        Transaction.Builder builder = new Transaction.Builder()
                .type(type)
                .from(nodeAddr)
                .to(deviceId)
                .amount(0)
                .data(payload)
                .nonce(nonce);

        Transaction tx = (privKey != null) ? builder.sign(privKey, pubKey).build() : builder.build();
        blockchain.addTransaction(tx);
```
**Note.** Fixing HC-004 *activates* HC-013. Fix both together.

**Regression test.** `hc004_iotIngressTransactionsAreUnsignable` must pass.

---

### HC-006 — Legacy SSL path installs a trust-all TrustManager
**Severity:** HIGH (transport authentication bypass) · **Status:** CONFIRMED · **Likelihood × Impact:** Medium × High

**Location.** `security/SSLUtils.java:99-112` — when `trustedCerts` is null/empty, `checkClientTrusted` and `checkServerTrusted` have empty bodies. Reached via `PeerNode`'s 3- and 4-argument constructors (`PeerNode.java:70-76`, both pass `new ArrayList<>()`), used by the second entry point `blockchain/App.java:93` and by every network test.

**Mitigation present.** The app-layer handshake (`PeerNode.java:617-676`) proves possession of *a* key, and P2P messages are signed. It does not bind that identity to the TLS session, so a relay/MITM can terminate both TLS legs (each trusts anything) and forward the handshake verbatim — reading all traffic and dropping/delaying it at will.

**PoC (executed).** `hc006_legacyPathAcceptsAnyCertificate`:
```
[HC-006] server verdict on attacker's self-signed cert: ACCEPTED CN=hbATTACKER
```

**Fix.** Fail closed.

`security/SSLUtils.java` — replace the trust-all branch:
```java
        TrustManager[] trustManagers;
        if (trustedCerts == null || trustedCerts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Refusing to build an SSLContext with no trust anchors (HC-006). "
                    + "Pass the peers' certificates or use createSSLContextWithCA.");
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        trustManagers = tmf.getTrustManagers();
```
Delete `blockchain/App.java` (or route it through the CA path). Update tests to supply certificates.

**Regression test.** `hc006_legacyPathAcceptsAnyCertificate` must pass (handshake rejected).

---

### HC-007 — Pre-authentication handshake allocates an attacker-chosen buffer
**Severity:** HIGH (unauthenticated remote DoS) · **Status:** CONFIRMED · **Likelihood × Impact:** High × High

**Location.** `PeerNode.java:630-634` (inbound) and `:659-663` (outbound):
```java
int challengeLen = in.readInt();
byte[] remotePubKey = new byte[33];
in.readFully(remotePubKey);
byte[] remoteSig = new byte[challengeLen - 33];   // unbounded, attacker-controlled
```
The connection handler catches `Exception` only (`:587`), so an `OutOfMemoryError` or `NegativeArraySizeException`-free `Error` escapes. Combined with HC-012 (no read timeout) a moderate length (e.g. 120 MB) is allocated and then pinned while the attacker stops sending: 3 connections per IP (`MAX_CONN_PER_IP = 3`) exhaust a 384 MB Raspberry Pi heap from one address.

**PoC (executed).** `hc007_preAuthHandshakeUnboundedAllocation`:
```
[HC-007] handshake with challengeLen=Integer.MAX_VALUE threw: java.lang.OutOfMemoryError: Java heap space
```

**Fix.** The signature is always 64 bytes (`Crypto.sign` returns `new byte[64]`, `Crypto.java:62`). Require the exact length, in both handshake methods:
```java
        int challengeLen = in.readInt();
        if (challengeLen != 33 + 64) {
            throw new IOException("Bad CHALLENGE length " + challengeLen);
        }
        byte[] remotePubKey = new byte[33];
        in.readFully(remotePubKey);
        byte[] remoteSig = new byte[64];
        in.readFully(remoteSig);
```
And validate the HELLO `len` field instead of discarding it (`if (in.readInt() != 40) throw new IOException("Bad HELLO length");`).

**Regression test.** `hc007_preAuthHandshakeUnboundedAllocation` must pass (IOException, no allocation).

---

### HC-008 — A per-node environment variable changes transaction validity
**Severity:** HIGH (determinism → chain split) · **Status:** LIKELY · **Likelihood × Impact:** Medium × High
**Consensus-rule change — requires coordinated upgrade.**

**Location.** `Config.java:40` (`REQUIRE_QUANTUM_SIG = getBooleanEnv("REQUIRE_QUANTUM_SIG", true)`) → `Transaction.java:324` → `Blockchain.validateTransaction:228`, which `applyBlockInternal` calls for every transaction of every incoming block.

**Description.** Validity of a transaction depends on a value each operator sets independently. Note the default is **true** in production but the whole test suite forces **false** (`pom.xml:287`), and the deployment guide previously told operators to set false.

**Scenario.** Mixed fleet: some nodes `true`, some `false`. A client submits ECDSA-only transactions. A `false` leader includes them; `true` nodes reject the block in `validateTransaction` → those nodes cannot apply it and stall or fork from the `false` nodes.

**Fix.** Make it a chain parameter. Interim, protocol-level guard — refuse peers with a different rule set, in `sendHello` and both handshake readers:
```java
    private static int ruleFlags() {
        return Config.REQUIRE_QUANTUM_SIG ? 1 : 0;
    }

    private void sendHello(DataOutputStream out, byte[] nonce) throws IOException {
        out.writeInt(MsgType.HELLO.ordinal());
        out.writeInt(44);
        out.writeInt(PROTOCOL_VERSION);
        out.writeInt(Config.NETWORK_ID);
        out.writeInt(ruleFlags());
        out.write(nonce);
        out.flush();
    }
```
with each reader doing `if (in.readInt() != 44) throw new IOException("Bad HELLO length");` … `if (in.readInt() != ruleFlags()) throw new IOException("Consensus rule mismatch (REQUIRE_QUANTUM_SIG)");` before reading the nonce, and `PROTOCOL_VERSION` bumped to 2. Long-term: store the flag in genesis/on-chain params and read it from chain state.

**Regression test.** Two `PeerNode`s with different `ruleFlags()` must fail the handshake.

---

### HC-012 — No socket timeouts, unbounded thread pool, half-open connections uncounted
**Severity:** HIGH (DoS that halts nodes) · **Status:** LIKELY · **Likelihood × Impact:** High × High

**Location.** `PeerNode.java:89,141` (`Executors.newCachedThreadPool()` — unbounded); no `setSoTimeout` anywhere in the file (grep); `:555` `MAX_PEERS` counts only established `peerConnections`, so connections stuck in the handshake are free; the ban check (`:564`) runs only after the handshake.

**Scenario.** Attacker opens connections from many addresses and sends nothing (or a partial HELLO). Each pins a platform thread and socket forever. Threads/file descriptors exhaust; with HC-007 memory exhausts too.

**Fix.** In the accept loop before `executor.submit`, and in the handler:
```java
                socket.setSoTimeout(10_000);          // handshake must finish in 10 s
```
after the handshake succeeds:
```java
                socket.setSoTimeout(120_000);         // idle timeout for established peers
```
replace both executors with a bounded pool:
```java
        this.executor = new java.util.concurrent.ThreadPoolExecutor(
                4, Config.MAX_PEERS * 2, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(Config.MAX_PEERS),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
```
and count in-flight handshakes (an `AtomicInteger` incremented before the handshake, decremented in `finally`) against `MAX_PEERS`; move the ban check before the handshake.

**Regression test.** Open `MAX_PEERS + 10` idle sockets to a node; assert a legitimate peer still connects after 11 s and that live thread count stays bounded.

---

### HC-013 — Unauthenticated telemetry can be attributed to any device (reputation poisoning)
**Severity:** HIGH · **Status:** LIKELY (currently masked by HC-004) · **Likelihood × Impact:** High × Medium

**Location.** `api/CoAPAdapter.java` `TelemetryResource.handlePOST` takes `deviceId` from the JSON body with no device authentication; `api/MQTTAdapter.java` takes it from the topic. `AbstractIoTMessageAdapter.submitDeviceTx` signs it **as the gateway** and sets `to(deviceId)`. In `Blockchain` TELEMETRY handling, `targetDeviceId = tx.getTo()` when registered, and `recordDeviceActivity(targetDeviceId, !isAnomaly)` updates **that device's** reputation.

**Scenario (after HC-004 is fixed).** Anyone with UDP reach to a gateway posts `{"deviceId":"victim-sensor","value":1e9}` repeatedly. Each is anomalous → the victim device's reputation drops on every block, with no credential required. Data integrity of every device's telemetry is likewise unauthenticated.

**Fix.** Require a device signature over the payload, verified against the key registered in the device's DID. Verified preconditions: `DeviceRecord` itself stores **no** device key, but `DeviceLifecycleManager.activateDevice(deviceId, owner, devicePublicKey, ts)` registers it via `ssiManager.registerDID(...)`, and `SSIManager` already provides `getDIDForDevice`, `isDIDRevoked` and `verifyDIDSignature` (`SSIManager.java:98,218,240`). `DeviceLifecycleManager.ssiManager` (line 241) has no accessor, so one is added.

`lifecycle/DeviceLifecycleManager.java` — add:
```java
    public com.hybrid.blockchain.identity.SSIManager getSsiManager() {
        return ssiManager;
    }
```
`api/AbstractIoTMessageAdapter.java` — add, and call it as the first line of `submitDeviceTx` (signature extended with `byte[] deviceSig`):
```java
    protected void verifyDeviceSignature(String deviceId, byte[] payload, byte[] deviceSig) {
        com.hybrid.blockchain.identity.SSIManager ssi =
                blockchain.getDeviceLifecycleManager().getSsiManager();
        String did = (ssi == null || deviceId == null) ? null : ssi.getDIDForDevice(deviceId);
        if (did == null || ssi.isDIDRevoked(did)) {
            throw new IllegalArgumentException("Unknown or revoked device " + deviceId);
        }
        if (deviceSig == null || !ssi.verifyDIDSignature(did, payload, deviceSig)) {
            throw new IllegalArgumentException("Bad device signature for " + deviceId);
        }
    }
```
Transport carriage of `deviceSig`: JSON field `"sig"` (hex) for CoAP, verified over the payload with that field removed; MQTT user property `sig` (MQTT 5) or the same JSON field. Batch 2 must confirm `DecentralizedIdentifier.verifySignature` hashes consistently with how devices sign.

**Regression test.** Telemetry for `victim-sensor` without a valid device signature is rejected and the victim's reputation is unchanged.

---

### HC-009 — `API_PORT` is ignored; an API failure leaves a zombie node
**Severity:** MEDIUM (operations; false health) · **Status:** CONFIRMED (observed live)

**Location.** `Config.java:60` defines `API_PORT`; nothing passes it to Spring (only logged at `blockchain/App.java:172`). `App.java:147` `SpringApplication.run(...)` has no failure handling while PBFT timer, proposer, P2P and sync threads are non-daemon.

**Evidence (live run).** With `API_PORT` set, Spring bound 8080; when 8080 was unavailable the log showed `APPLICATION FAILED TO START … Port 8080 was already in use` and the process stayed alive, running consensus with no API — `systemctl status` would report it healthy and `Restart=always` would never fire.

**Fix.** `com/hybrid/App.java`, replace line 147:
```java
        if (System.getenv("SERVER_PORT") == null && System.getProperty("server.port") == null) {
            System.setProperty("server.port", String.valueOf(Config.API_PORT));
        }
        try {
            SpringApplication.run(IoTRestAPI.class, args);
        } catch (Throwable t) {
            log.error("[STARTUP] REST API failed to start — terminating so the supervisor can restart", t);
            System.exit(1);
        }
```
(When applied, docs return to port 8000; currently they correctly say 8080.)

**Regression test.** Start with the API port occupied; assert the process exits non-zero within 60 s.

---

### HC-010 — `KeygenTool` default seed yields publicly computable validator keys
**Severity:** MEDIUM (hazardous default) · **Status:** CONFIRMED (code) — no shipped config found using it

**Location.** `tools/KeygenTool.java:28` `String seed = args.length >= 2 ? args[1] : "hybridchain-key-seed";` then `priv_i = SHA-256(seed + ":" + i) mod (n−1) + 1`.

**Fix.**
```java
        if (args.length < 2) {
            byte[] r = new byte[32];
            new java.security.SecureRandom().nextBytes(r);
            System.err.println("No seed given: using a fresh random seed (keys are NOT reproducible).");
            seed = HexUtils.encode(r);
        } else {
            seed = args[1];
            if (seed.length() < 32) {
                System.err.println("Seed must be >= 32 characters of secret randomness.");
                System.exit(1);
            }
        }
```
(declare `String seed;` instead of the ternary). **Regression test:** running with one argument twice yields different keys.

---

### HC-011 — Handshake authenticates key possession but never authorisation
**Severity:** MEDIUM · **Status:** LIKELY

**Location.** `PeerNode.java:617-676` derive `Crypto.deriveAddress(remotePubKey)` and accept it with no membership check; `PeerManager` eclipse protection is count-based.

**Scenario.** An attacker with many IPs (3 connections each) fills a target's peer slots with fresh keys, becoming its entire view of the network — censoring or delaying consensus traffic (eclipse).

**Fix.** For validators, admit only known identities:
```java
    private boolean peerAllowed(String address) {
        if (consensus == null) return true;
        boolean known = consensus.isValidator(address) || Config.PEER_ALLOWLIST.contains(address);
        return known || !(Config.NODE_ROLE == Config.NodeRole.VALIDATOR);
    }
```
checked immediately after each handshake returns (`if (!peerAllowed(remotePeerId)) throw new IOException("Peer not authorised");`), with `Config.PEER_ALLOWLIST` parsed from a comma-separated env var.

---

### HC-014 — NPE in the proposer loop when no leader is eligible
**Severity:** LOW · **Status:** LIKELY

**Location.** `App.java:231` `pbft.getCurrentLeader().equals(nodeId)`; `selectLeader` returns `null` when the ZK gate leaves no eligible validator. Caught by the loop's `catch (Exception)` and logged every tick.

**Fix.** `if (nodeId.equals(pbft.getCurrentLeader())) {`

---

### HC-015 — Test configuration masks production behaviour
**Severity:** INFO (but root cause of three CRITICAL/HIGH escapes) · **Status:** CONFIRMED

`pom.xml:287` forces `-DMIN_PEERS=1` (masks HC-001) and `-DREQUIRE_QUANTUM_SIG=false` (opposite of the production default, HC-008). `MTLSSecureNetworkTest` uses the trust-all legacy constructor (masks HC-005 and HC-006). **Fix:** remove both overrides; add production-wired network tests; keep `HybridChainAuditPoCTest` in CI once fixes land.

---

## Previously remediated (re-checked, not re-reported)
S1-01 (block application rollback), S7-01 (hash-bound commit certificate), E1–E3 (leader-selection determinism), JWT secret/role issues, signature malleability, admin config bounds. Their regression tests pass in the current suite.

---

## Remaining scope (batches 2+) — not yet audited line-by-line

| Area | Files |
|---|---|
| Ledger state machine | `Blockchain.java` (validation/apply/fork paths beyond S1-01), `AccountState.java`, `UTXOSet.java`, `Mempool.java`, `FeeMarket.java`, `Tokenomics.java`, `TokenRegistry.java`, `MerklePatriciaTrie.java` |
| Consensus remainder | view change / NEW_VIEW, checkpoints & watermarks, `PeerNode` sync & `applyBlockAtSequence`, `PoAConsensus.java` |
| Crypto | `Crypto.java`, `Transaction.java` canonical encoding, `QuantumResistantCrypto.java`, `ZKProofSystem.java` (soundness review), `Storage.java` AES-GCM usage |
| Contracts | `Interpreter.java`, `WasmContractEngine.java` (host functions, determinism, gas) |
| Governance/identity | `GovernanceManager.java`, `MultiSigManager.java`, `SSIManager.java`, DID/VC, `DeviceLifecycleManager.java`, `HardwareManager.java` |
| API | `IoTRestAPI.java` endpoint-by-endpoint, WebSocket, `RateLimiter.java` |
| AI | `FederatedLearningManager.java`, `ProofOfInference.java`, anomaly/threat scorers |
| Checkpoint fast-sync | `Checkpoint.java`, `Blockchain.init` fast-sync path (note: two new pentests target this) |
| Build/deps | `pom.xml` dependency versions & CVEs, Dockerfile, compose files, scripts |
| Report parts | **Parts 4–9** (missing features, architecture, roadmap, domain expansion, CI plan, consolidated fix plan) are produced after all modules are reviewed, so they reflect the whole system rather than batch 1. |
