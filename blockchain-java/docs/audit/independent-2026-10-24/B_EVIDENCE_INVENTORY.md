# B — Repository Evidence Inventory

## 1. Repository facts

| Item | Value | Command |
|---|---|---|
| Remote | `https://github.com/marcamgad/Blockchain.git` | `git remote -v` |
| Branch / HEAD | `main` / `12fb38549606e5c07026e1741da2dbe5ac73a7c8` | `git rev-parse HEAD` |
| `origin/main` | `fd5df742…` (adds `SECURITY.md`; local is 10 commits ahead, 1 behind) | `git log HEAD..origin/main` |
| Working tree | clean (apart from these documents) | `git status --short` |
| Languages / build | Java 17, Maven (`blockchain-java/pom.xml`); a legacy JavaScript prototype at the repository root (`package.json`, `*.js`), not built or tested | `find -name pom.xml` |
| Size | 91 main source files (≈ 21,600 lines), 175 test files (≈ 21,000 lines) | `wc -l` |
| Entry point | `com.hybrid.App` (Spring Boot jar); legacy `com.hybrid.blockchain.App` | pom, Dockerfile |
| CI | `.github/workflows/ci.yml` (verify, harness self-test, SBOM, OSV report-only), `weekly-audit.yml` (SpotBugs, PIT, audit PoCs), `dependabot.yml` | |
| Formal | `docs/formal/LeaderSelect.tla` (old leader rule only) | |
| Live harness | `harness/fault-injection/` (single-host Docker, pumba, libfaketime) | |
| Licence | Non-Commercial Source-Available (C-22) | `LICENSE` |

## 2. Environment

Windows 11 amd64, 8 logical CPUs; OpenJDK 17.0.18; Maven 3.9.14 (offline local repository); Docker
Desktop available but not used for this audit (the user's harness may have been running).

## 3. Commands run and results

| # | Command (run in the clean worktree at `12fb385`) | Result |
|---|---|---|
| 1 | `git worktree add --detach <scratchpad>/audit-wt HEAD` | clean checkout for all builds and probes |
| 2 | `mvn -o -B verify` (in `blockchain-java/`) | **BUILD SUCCESS in 3 min 01 s; 724 tests run, 0 failures, 0 errors, 0 skipped; all JaCoCo coverage rules met** |
| 3 | JaCoCo CSV summary | **line 76.9 %, branch 63.5 %**; `PeerNode` 50.3 % / 38.5 %; `IoTRestAPI` 34.3 % / 37.9 %; `com.hybrid.App` 0 %; `PBFTConsensus` 90.2 % / 74.6 %; `Blockchain` 85.7 % / 70.9 % |
| 4 | test discovery | 706 test annotations in sources; 724 executions (parameterised expansion); 169 test classes executed; **30 audit PoC tests excluded by default** (tag `audit-poc`, run weekly in CI); `NetworkHardeningScanner`, `SeedCheck` excluded by pom |
| 5 | audit PoC suite (`-Dtest=HybridChainAuditPoCTest -Daudit.excluded=none`, run during the HC-091 work on the same code) | 30/30 pass |
| 6 | `AuditProbes` (scratchpad, production mode) | F §2 and C-1 |
| 7 | `mvn -o -Paudit-static … spotbugs:spotbugs` | **not run**: plugin 4.10.5.0 not in the offline repository; downloading needs approval. CI runs it weekly. |
| 8 | PIT mutation testing | **not run** (same reason) |
| 9 | OSV / dependency vulnerability scan | **not run locally**; CI report-only job exists, but its artifacts need GitHub authentication |

## 4. Tests vs. the properties claimed

| Property claimed | Meaningfully exercised by |
|---|---|
| One vote per (view, height) | `ProposalVotingTest`: component level, real `PeerNode`, no network |
| Atomic block persistence | `BlockPersistenceAtomicityTest`: simulated exception, not a process kill |
| Deterministic state root | `StateRootV2Test` (golden, round trip), `LedgerPropertyTest` (seeded permutations): one platform |
| Byzantine safety | `ByzantineFaultInjectionTest`: in-process messenger, randomised faults, 1 equivocating voter |
| Live liveness under faults | Docker harness: single host, last full run before HC-087 to HC-093 |
| Supply conservation | audit PoCs: example-based, not a stateful model |

Line coverage of consensus classes is high (90 %), but the restart property fails (C-1). That's the
reason coverage is not used as a measure of security here.

## 5. Inspected

**Source:**
- `consensus/PBFTConsensus.java`
- `PeerNode.java` (handlers, handshake, sync)
- `com/hybrid/App.java`
- `Blockchain.java` (`validateBlock`, `applyBlockInternal`, `init`, telemetry path)
- `Block.java`, `Transaction.java`
- `AccountState.java` (state root, readings)
- `Storage.java`, `Mempool.java`, `FeeMarket.java`, `Crypto.java`
- `security/SSLUtils.java`, `security/CertificateAuthority.java`, `security/SecurityConfig.java`
- `api/WebSocketConfig.java`, `api/EventBusWebSocketHandler.java`
- `lifecycle/DeviceReading.java`, `identity/SSIManager.java`
- `privacy/ZKProofSystem.java` (verification entry points)
- `ai/DPMechanism.java`, `WasmContractEngine.java` (instruction policy)

**Other:** `Dockerfile`, `LICENSE`, `SECURITY.md` (origin), `pom.xml` (test configuration), and the
`docs/` reports, which were checked against the code rather than relied on.

## 6. Not inspected or not established

- **Not reviewed in detail:**
  - `Interpreter` opcode semantics;
  - `MerklePatriciaTrie` proof verification;
  - `DeviceLifecycleManager` action rules;
  - `FederatedLearningManager` aggregation;
  - `TelemetryAnomalyDetector` statistics;
  - `MQTTAdapter`/`CoAPAdapter` parsing;
  - `TokenRegistry`, `UTXOSet`;
  - the legacy JavaScript prototype.

  Earlier internal audit rounds covered most of these (`docs/audit/`). They were not re-derived here.
- **Not established:** static-analysis findings, mutation score, dependency vulnerabilities (8–9 above); behaviour on ARM64; behaviour on separate machines; performance under load; live behaviour after HC-087 to HC-093.
- **External comparator facts:** dated and cited in E. Versions of Bitcoin Core, Geth and Chainlink were not re-verified.
