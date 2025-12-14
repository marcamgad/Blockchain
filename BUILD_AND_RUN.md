# Build & Run Guide

**Last Updated:** December 14, 2025  
**Status:** ✅ Compiles & runs successfully

## Quick Start

### Prerequisites
- Java 11+ (tested with JDK 17)
- Maven 3.6+
- Git

### Build

```bash
cd ~/blockchain-js/blockchain-java
mvn clean compile
```

### Run Tests
```bash
mvn exec:java -Dexec.mainClass="com.hybrid.blockchain.TestBlockchain"
```

**Expected Output:**
```
[INIT] Creating genesis block
[INFO] Blockchain initialized. Current height: 0
[INFO] New block added. Height: 1
[INFO] Block hash: 0000...
[PRUNE] Removed block 0
[SNAPSHOT] Saved at height 0
...
[INFO] Final blockchain height after pruning: 4
[INFO] BUILD SUCCESS
```

## Recent Fixes Applied ✅

| Issue | Fix | File |
|-------|-----|------|
| Invalid AES key (17 bytes) | Changed to 16-byte `"1234567890abcdef"` | `Config.java` |
| Network ID type mismatch | Fixed String vs int comparison | `Blockchain.java:115` |
| Snapshot recovery crash | Added graceful fallback to tip-hash recovery | `Blockchain.java:28-60` |
| Stale encrypted data | Removed legacy DB folders | (cleanup) |

## Current Rating

**5/10 → 6/10** after fixes

**Strengths:**
- ✅ Core blockchain primitives (blocks, transactions, UTXO, accounts)
- ✅ PoA consensus with validator signing
- ✅ Storage with LevelDB encryption
- ✅ Snapshots & pruning
- ✅ Mempool with fee-based ordering

**Gaps (see `PRODUCTION_CHECKLIST.md` for details):**
- ⚠️ Plain AES encryption (not AES-GCM)
- ⚠️ Hardcoded encryption key
- ⚠️ No P2P networking (PeerNode is stub)
- ⚠️ No REST/gRPC API
- ⚠️ No unit tests (only integration test)
- ⚠️ No metrics/logging
- ⚠️ No validator management
- ⚠️ Not thread-safe (for multi-threaded use)

## Project Structure

```
blockchain-java/
├── src/main/java/com/hybrid/
│   └── blockchain/
│       ├── App.java                 # Entry point (stub)
│       ├── Block.java               # Block with PoW mining
│       ├── Blockchain.java          # Core ledger (recently fixed)
│       ├── Config.java              # Config constants (AES key fixed)
│       ├── Consensus.java           # Consensus interface
│       ├── ContractVM.java          # Smart contracts (stub)
│       ├── Difficulty.java          # PoW difficulty adjustment
│       ├── IdentityManager.java     # Key/identity mgmt (stub)
│       ├── Mempool.java             # Transaction pool
│       ├── PeerNode.java            # P2P networking (stub)
│       ├── PoAConsensus.java        # Proof of Authority
│       ├── PrunedBlockchain.java    # Blockchain with pruning
│       ├── Storage.java             # LevelDB with encryption
│       ├── TestBlockchain.java      # Integration test ✅
│       ├── Transaction.java         # Tx with ECDSA & UTXO
│       ├── UTXOInput.java           # UTXO reference
│       ├── UTXOOutput.java          # UTXO output
│       ├── UTXOSet.java             # UTXO management
│       ├── Utils.java               # Utility functions
│       └── Validator.java           # Validator info
├── pom.xml                          # Maven config
└── target/                          # Build output
```

## Compile Warnings

Expected warnings (safe to ignore for now):
```
[WARNING] /src/main/java/.../Storage.java: Some input files use unchecked or unsafe operations.
```

These are harmless type-safety warnings from Jackson deserialization. Will be fixed when migrating to protobuf/CBOR.

## Common Commands

### Clean & compile
```bash
mvn clean compile
```

### Run tests
```bash
mvn test  # (Currently: no formal unit tests)
```

### Run integration test
```bash
mvn exec:java -Dexec.mainClass="com.hybrid.blockchain.TestBlockchain"
```

### Build fat JAR
```bash
mvn assembly:assembly -DdescriptorId=jar-with-dependencies
```

### Run with custom key (future)
```bash
export BLOCKCHAIN_STORAGE_KEY="my-16-byte-key!!"
java -cp target/blockchain-java-1.0-SNAPSHOT-jar-with-dependencies.jar \
  com.hybrid.blockchain.App
```

## Database

Location: `./data/chain-db/` (LevelDB format)

**To reset:**
```bash
rm -rf ./data/chain-db
```

**Backup:**
```bash
cp -a ./data/chain-db ./data/chain-db.bak
```

## Next Steps

1. **Read the Production Checklist** (`../PRODUCTION_CHECKLIST.md`) for roadmap
2. **Implement quick wins** (SLF4J logging, config externalization)
3. **Add AES-GCM encryption** (see section 1 of checklist)
4. **Add unit tests** (Transaction, UTXO, PoA)
5. **Implement P2P & API** (see section 3 of checklist)

## Troubleshooting

### Build fails: "AES key must be 16, 24, or 32 bytes"
- Fixed! The default key is now 16 bytes. If you see this:
  - Clear Maven cache: `mvn clean`
  - Or manually set key in `Config.java`

### Build fails: "Wrong networkId"
- Fixed! Network ID comparison now works correctly.
- If you still see this, ensure `Config.NETWORK_ID` matches transaction validation.

### Database locked error
- LevelDB: `LOCK` file exists
  - Solution: `rm ./data/chain-db/LOCK` (if no other process is running)

### No blocks created
- Check: Are validators initialized in TestBlockchain?
- Check: Is PoAConsensus correctly initialized?

## Development Tips

### Enable debug logging
Add to `logback.xml` (when you create it):
```xml
<root level="DEBUG">
```

### Increase heap for large chains
```bash
export MAVEN_OPTS="-Xmx4G"
mvn exec:java ...
```

### Profile with JProfiler
```bash
mvn exec:java@profile
```

---

**Questions?** See `PRODUCTION_CHECKLIST.md` for security & feature roadmap.
