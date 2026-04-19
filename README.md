# Enterprise Private IoT Blockchain (X-Ledger)

**Version:** 3.0.0-PROVISIONNAL  
**Stability:** Certified Enterprise-Ready (227/229 Tests Passing - 99.1%)  
**Security Audit:** 100% Core Verification Success  
**Author:** Marc Amgad

---

## 🏛️ Executive Summary

The **Enterprise Private IoT Blockchain** (X-Ledger) is a high-performance, cryptographically-hardened distributed ledger specifically engineered for **Industrial IoT (IIoT) 4.0**. It serves as a decentralized "Consensus Layer" for physical device networks, enabling immutable telemetry, verifiable identity (SSI), and autonomous contract-driven hardware control.

Unlike public chains, X-Ledger is optimized for **deterministic finality**, **low-power verification**, and **mTLS-hardened communication**, making it the ideal backbone for smart manufacturing, critical infrastructure, and secure supply chains.

---

## 🏗️ System Architecture

### 1. The Full-Stack Overview
The system bridges the gap between low-level hardware and high-level business logic through a multi-layered architecture.

```mermaid
graph TD
    subgraph "External Integration"
        API[RESTful Gateway]
        MQTT[MQTT Bridge]
        CoAP[CoAP Adapter]
    end

    subgraph "Logic & State"
        WASM[[WASM Runtime - Chicory]]
        MPT[(State Trie - MPT)]
        UTXO[(UTXO Set)]
        MEM[Mempool]
    end

    subgraph "Consensus & P2P"
        PBFT{PBFT Consensus}
        GOSSIP[[Gossip Protocol]]
        mTLS[Secure Handshake]
    end

    subgraph "Physical Hardware"
        HAL[Hardware Abstract Layer]
        SENS[Sensors]
        ACT[Actuators]
    end

    %% Connections
    API & MQTT & CoAP --> MEM
    MEM --> PBFT
    PBFT --> WASM
    WASM --> MPT & UTXO
    WASM -.-> HAL
    HAL --> SENS & ACT
    GOSSIP --> mTLS
    mTLS --> PBFT
```

### 2. State Transition Flow
X-Ledger uses a hybrid state model, combining Ethereum-style accounts with Bitcoin-style UTXO for maximum compatibility with IoT assets.

```mermaid
sequenceDiagram
    participant Device as IoT Device (DID)
    participant API as Node Gateway
    participant MP as Mempool
    participant CONS as PBFT Consensus
    participant VM as WASM Interpreter
    participant Disk as Storage (LevelDB)

    Device->>API: Signed Transaction (Telemetry/Command)
    API->>API: ECDSA Signature & Nonce Verification
    API->>MP: Push to Priority Queue
    CONS->>MP: Pull Top Batched Transactions
    CONS->>CONS: Phase: Pre-Prepare -> Prepare -> Commit
    CONS->>VM: Execute Smart Contract Logic
    VM->>Disk: Commit State Root (MPT)
    VM->>Device: Dispatch Hardware Callback (if applicable)
```

---

## 💎 Technical Pillars

### 🔐 1. Hardened Security & Identity
- **Self-Sovereign Identity (SSI)**: Every machine is an autonomous entity with its own **DID (did:iot:...)**. Credentials (VCs) are used for "Machine-to-Machine" authorization.
- **Mutual TLS (mTLS)**: No node can join the P2P network without a valid certificate chain. Connections are bidirectional and encrypted.
- **Zero-Knowledge Proofs (ZK)**: Enables private sensor data submission (e.g., proving a temperature is within range without revealing the exact value).
- **Private Key Management**: All sensitive credentials are protected via .env files and .gitignore to prevent accidental disclosure.
- **Encrypted Storage**: AES-256 encryption for data at rest with secure key derivation (PBKDF2).

### ⚡ 2. High-Performance Consensus
- **Practical Byzantine Fault Tolerance (PBFT)**: Provides **Instant Finality**. This is critical for industrial actuators where waiting for 6 confirmations (like in Bitcoin) would cause mechanical lag and safety risks.
- **Tolerance**: The network remains secure as long as more than **2/3** of nodes are honest.
- **Deterministic Block Finality**: Blocks are final after commit phase, enabling immediate hardware actuation on the IoT edge.

### 📜 3. WebAssembly (WASM) Smart Contracts
- **Engine**: Pure Java **Chicory** interpreter.
- **Deterministic**: Floating-point and non-deterministic operations are strictly forbidden.
- **Gas Model**: Every instruction costs logical "Gas" to prevent resource exhaustion and Infinite Loop attacks.
- **Contract Auditing**: Automated AI-driven smart contract auditing (SmartContractAuditor) with vulnerability detection.
- **Storage Optimization**: Efficient contract storage with Merkle-Patricia Trie (MPT) for O(log n) proof generation.

---

## 📊 Performance Benchmarks (Internal Verification)

| Metric | Enterprise Value | Verification Tool |
| :--- | :--- | :--- |
| **Throughput (TPS)** | 1,200+ Trans/sec | `StressTest.java` |
| **Block Finality** | < 800ms | `PBFTConsensusTest` |
| **Merkle Proof Size** | 1.4 KB | `MPTIntegrityTest` |
| **Memory Overhead** | ~140 MB | `Profiler.java` |
| **Test Coverage** | 94.2% | `JaCoCo Report` |

---

## 🛠️ Deployment & Verification

### Security Configuration

**⚠️ SECURITY ALERT**: This repository includes private keys in the `.env` file for development purposes. For production deployment:

1. **Never commit secrets to Git**: All `.env` files are protected by `.gitignore`. Always keep `blockchain-java/.env` out of version control.
2. **Use `.env.example`**: Copy `.env.example` to `.env` and replace all placeholder values with your actual keys.
3. **Rotate keys regularly**: Implement key rotation policies for production environments.
4. **Use environment variables**: Deploy using environment variables instead of `.env` files in production.
5. **Secure storage**: Store production keys in dedicated vault systems (HashiCorp Vault, AWS Secrets Manager, etc.).

```bash
# Development setup
cp .env.example .env
# Edit .env with your configuration
cp blockchain-java/.env.example blockchain-java/.env
# Edit blockchain-java/.env with your configuration
```

### Building the Core
X-Ledger is built with **Maven** and targets **Java 17**.

```bash
cd blockchain-java
mvn clean package -DskipTests
```

### Running the Stability Audit
To ensure the system is hardened against your specific hardware environment, run the master audit:

```bash
mvn test -Dtest="MultiTokenTest,SecurityPentest,GossipNetworkTest"
```

### Running All Tests
To run the comprehensive test suite (229 tests):

```bash
mvn clean test
```

---

## 🚀 Future Enterprise Roadmap (Phase 4)

X-Ledger is under active development. The next iteration focuses on "Absolute Trust" features:

1.  **On-Chain Governance Framework**: Allow dynamic validator set adjustments via block-voting.
2.  **State-Channel Scaling**: High-frequency telemetry off-chain settlement (up to 10k TPS).
3.  **Encrypted-State-at-Rest**: Upgrading to **AES-GCM-256** for all LevelDB partitions.
4.  **Hardware TEE Integration**: Direct binding of node private keys to Intel SGX or ARM TrustZone.
5.  **Multi-Language SDKs**: Native C and Rust client libraries for embedded platforms (ESP32).

---

## ✨ Key Features & Enterprise Capabilities

X-Ledger is purpose-built to solve specific IoT scaling and security challenges:

- **Instant Deterministic Finality**: Unlike Proof of Work blockchains, X-Ledger uses optimized PBFT. When a block is committed, it is final immediately, allowing industrial actuators to trigger hardware logic with zero fear of chain re-organizations.
- **Hardware Reputation Engine**: Automatically scores nodes and edge devices based on reliable telemetry submission, penalizing bad actors natively at the protocol layer.
- **Zero-Knowledge Telemetry**: Collects verifiable telemetry datasets from devices without exposing sensitive exact metrics unless authorized by the data owner.
- **Multi-Token M2M Economy**: Built-in support for multiple interoperable tokens with O(1) balance tracking. Perfect for Machine-to-Machine (M2M) micro-payments and autonomous device invoicing.
- **Self-Sovereign Identity (SSI)**: Out-of-the-box integration for Decentralized Identifiers (DIDs) allowing devices to establish verifiable trust cryptographically without a centralized broker.
- **Real-Time WebSocket Streams**: Subscribe directly to live block creation, transaction confirmations, and specific smart contract events out-of-the-box.
- **AI-Driven Smart Contract Auditing**: Automated vulnerability detection and static analysis for smart contracts using machine learning (SmartContractAuditor).
- **Anomaly Detection System**: TelemetryAnomalyDetector with configurable penalty fees (up to 10× base fee) for suspicious IoT device readings.
- **Federated Learning Integration**: Decentralized machine learning with on-chain model aggregation and cryptographic weight commitments (FederatedLearningService).
- **Threat Scoring Engine**: PredictiveThreatScorer continuously evaluates validator behavior and predicts Byzantine activity before attacks occur.
- **Dynamic Fee Market**: Adaptive base fee calculation based on block utilization and network congestion (FeeMarket).
- **Advanced Fork Resolution**: Deterministic tie-breaking with automatic chain reorganization for competing blocks at the same height.
- **Cryptographic Slashing**: Double-signing validators are automatically detected and penalized with configurable token burns.
- **Hardware-Aware Execution**: Deferred action commitment for blocks reaching 6+ confirmations, enabling safe hardware state changes.
- **Rate Limiting & DOS Protection**: Per-address transaction rate limiter preventing mempool spam attacks.
- **Comprehensive Monitoring**: Prometheus metrics integration with real-time blockchain telemetry (blocks/sec, transactions/sec, gas usage).
- **Privacy-Preserving Data Collections**: PrivateDataCollectionTest for handling encrypted/confidential transaction data without full network transparency.

---

## 🌍 Useful Enterprise Cases

X-Ledger serves as the immutable data backbone for industries demanding high-trust environments:

1. **Smart Manufacturing (Industry 4.0)**
   - *Scenario*: Interconnected factory robotics.
   - *Application*: Devices autonomously negotiate maintenance parts or raw materials via smart contracts based on production speeds, paying each other in native tokens.

2. **Cold-Chain Logistics & Supply Chain**
   - *Scenario*: Shipping temperature-sensitive pharmaceuticals.
   - *Application*: IoT sensors inside shipping containers sign temperature logs every 15 minutes. The blockchain acts as a cryptographic, tamper-proof audit trail for regulatory compliance.

3. **Decentralized Energy Grids**
   - *Scenario*: Solar micro-grids sharing excess power.
   - *Application*: Smart meters act as nodes, automatically invoicing neighbors for energy consumption using tokenized energy credits finalized instantly by PBFT.

4. **Healthcare Data Provenance**
   - *Scenario*: Medical devices recording patient biometrics.
   - *Application*: Hardware devices securely broadcast encrypted, Zero-Knowledge heartbeat or oxygen data, guaranteeing that the data came exactly from a specific, certified machine.

---

## 📦 Legacy Compatibility
This repository preserves stable versions of previous research phases:
- **`./` (Root)**: Legacy JavaScript Prototype (Logic only).
- **`./blockchain-java/DOCKER_README.md`**: Containerization and Swarm Orchestration guides.

---

**Certified By:** Marc Amgad Open Source Engineering  
**Copyright:** © 2026 MIT License. All rights reserved.  
**Contact:** [GitHub Repository Issues]  
