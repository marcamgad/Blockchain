# HybridChain — upgrade and code-health audit (2026-10-23)

Companion to [`GAP_ANALYSIS.md`](GAP_ANALYSIS.md), which covers missing features and capabilities.
This document covers what to **upgrade or clean up** in the existing code and toolchain. Numbers
come from the code at `c0d23db` and the JaCoCo report of the last full `mvn verify` (707 tests).

## 1. Platform and dependencies

| # | Item | Now | Upgrade to | Why | Effort |
|---|---|---|---|---|---|
| U1 | **Spring Boot** | 3.5.16 | **4.1.x** | 3.5's free support ended in June 2026, and 3.5.16 was its last free release. New Spring CVEs will not get free 3.5 fixes. 4.0's free support ends December 2026, so go straight to 4.1. | Large: Spring Framework 7, Spring Security 7, Jakarta EE 11, and Jackson 3 as the default JSON library. Do it on its own branch, with the full suite and a harness run as the gate. |
| U2 | **Java** | 17 | **21** (or 25) | Oracle premier support for 17 ended in September 2026. 21 adds virtual threads, which suit `PeerNode`'s one-thread-per-connection model. 25 is the newest long-term-support release. | Small: compiler level, Docker base image `eclipse-temurin:21-jre`. |
| U3 | jjwt — *done 2026-10-23: 0.13.0* | 0.11.5 (2022) | 0.13.x | 0.12 replaced the builder API (`setSubject`/`signWith(key)` are deprecated). Only `JwtManager` uses it. | Small |
| U4 | LevelDB (`leveldbjni-all` 1.8, `iq80 leveldb` 0.12) | 2013 / 2016 | RocksDB (`rocksdbjni`) | Both are unmaintained. The JNI build has no ARM64 binary (Pi falls back to the pure-Java port, which is slower). RocksDB is maintained, ships ARM64 binaries, and is what most chains use. | Medium: everything goes through `Storage`. Needs a one-time data migration tool, or fresh networks only. |
| U5 | Paho MQTT v3 client | 1.2.5 (2020) | Paho MQTT v5 or the HiveMQ client | v3 is in low-maintenance mode. MQTT 5 adds per-message expiry and reason codes, which are useful for devices. | Small: one adapter (`api/MQTTAdapter`). |
| U6 | Californium CoAP | 3.10.0 | latest 3.x, plus **Scandium (DTLS)** | DTLS is missing (GAP_ANALYSIS S3); Scandium is Californium's own DTLS module. | Medium |
| U7 | Guava 33.3.1, commons-io 2.16.1, msgpack 0.9.12, chicory 1.0.0 — *done 2026-10-23 except chicory (still 1.0.0, own PR + harness run)* | — | current releases | Routine. Check chicory first: the WASM runtime is consensus-critical (gas and results must be identical on every node), so pin the exact version and run the WASM tests. | Small |
| U8 | Build plugins: compiler 3.11.0, cyclonedx 2.7.11, spotbugs 4.8.6.6, pitest 1.15.3 — *done 2026-10-23* | — | current releases | Routine. | Small |

## 2. Code health

| # | Finding | Evidence | Recommendation |
|---|---|---|---|
| H1 | **Dead code** — *done 2026-10-23* | Deleted: the `iot` package (`CoAPAdapter`, `MQTTAdapter`, `IoTStateChannel`: unwired telemetry-batching and state-channel research code, 0% coverage, duplicating the live adapters in `api/`), `IdentityManager`, `ai/ProofOfInference`, `examples/MPTUsageExample`. Kept: `ABIEncoder`/`ABIDecoder`, which the node does not use but which are the only client-side helper for contract call data. | Move the ABI helpers into the client SDK (GAP_ANALYSIS A1). Git history keeps the deleted research code. |
| H2 | **Governance is unwired** | `MultiSigManager` (479 lines) and `GovernanceManager` are only used by tests. | Either remove them or rebuild as on-chain transactions (GAP_ANALYSIS C1). Do not wire the current class: its proposal state is node-local and expiry uses the wall clock. |
| H3 | **Proof-of-work leftovers** | `Difficulty`, `Block.mine`, and a `difficulty` field in every block header, hashed into the block. It comes from node-local state, is never validated by receivers, and its adjustment reads `Config.TARGET_BLOCK_TIME_MS`, which the admin API can change. | Remove it from new blocks behind an activation height (it changes the block hash); keep decoding old blocks. |
| H4 | **Admin API changes network parameters on one node** — *done 2026-10-23 (HC-086): endpoint removed* | `POST /api/v1/admin/config/update` writes `Config.MAX_TRANSACTIONS_PER_BLOCK` and `Config.TARGET_BLOCK_TIME_MS`. The proposer interval is fixed at start-up, so a block-time change only alters that node's view-change timeout, while the API reports the new value as if it applied. Neither change is replicated. | Remove the endpoint, or make it read-only. Network parameters must change through an on-chain upgrade (`docs/design/PROTOCOL_UPGRADES.md`). |
| H5 | Mutable static configuration — *done for the network parameters (HC-086); `BYPASS_CONTRACT_AUDIT` (test-only, refused in production) and `NODE_ID` (a label) stay mutable until the tests are refactored* | 5 non-final `public static` fields in `Config` (`TARGET_BLOCK_TIME_MS`, `MAX_TRANSACTIONS_PER_BLOCK`, `BYPASS_CONTRACT_AUDIT`, `NODE_ID`, `MAX_NONCE_GAP`). | Make them final (after H4). Tests that change them should use a constructor-injected config object instead. |
| H6 | Very large classes | `Blockchain` 2,007 lines, `PeerNode` 1,322, `IoTRestAPI` 1,232, `PBFTConsensus` 1,182. | Split along the existing seams: `Blockchain` into block validation, state transition and chain storage; `PeerNode` into transport/handshake, gossip and sync; `IoTRestAPI` into one controller per resource. Do this after U1, since the Spring migration touches the REST code anyway. |
| H7 | Weakest test coverage is in the network-facing code | Overall: 75.3% lines, 63.1% branches. `PeerNode` 47% of lines (394 missed), `IoTRestAPI` 35% (320 missed), `api` package 51% lines / 46% branches. | Add JaCoCo floors for `api` and `PeerNode`, as done for `consensus` (branch ≥ 0.73), and raise them as tests land. The live harness exercises `PeerNode` but does not count towards JaCoCo. |
| H8 | Errors swallowed silently | 23 empty or `ignored` catch blocks in main code. | Review each one: log at debug level at minimum, or count it in metrics. |
| H9 | `new ObjectMapper()` per call | 26 places, including the P2P send path (one per message). | Use one shared, configured mapper. It's faster and gives consistent serialisation settings (important for anything hashed or signed). |

## 3. Process and tooling

| # | Item | Recommendation |
|---|---|---|
| P1 | **No CI** — *added 2026-10-23: `.github/workflows/ci.yml` and `weekly-audit.yml`; first runs pending* | GitHub Actions: `mvn -B verify` (tests and coverage floors), `py harness/fault-injection/check.py --self-test`, CycloneDX SBOM plus an OSV dependency scan. Already configured in the pom but not run automatically: SpotBugs/FindSecBugs and PIT. Run those weekly. |
| P2 | Dependency updates are manual | Enable Dependabot or Renovate for Maven and Docker base images. Group minor updates, keep major ones manual. |
| P3 | Live harness runs only by hand | Run the 4-validator harness nightly on a self-hosted runner (it needs Docker, and takes about an hour). |
| P4 | Commit and branch practice | One maintainer, direct commits to `main`. Use branches and pull requests so CI gates every change. |

## Suggested order

1. **P1 CI**: everything after this is checked automatically.
2. **H1 dead code, H4 admin endpoint, H5 final config**: small, and they remove traps.
3. **U2 Java 21** and **U3 jjwt**: small.
4. **U1 Spring Boot 4.1** on its own branch, gated by the full suite plus a 4- and 7-validator harness run.
5. **H6 split the large classes**, then **H7 coverage floors**.
6. **U4 RocksDB**, **U5 MQTT 5**, **U6 DTLS**: with the IoT work (GAP_ANALYSIS I1, S3).
7. **H3 remove difficulty**: with the next protocol activation, together with other header changes.

Version facts (Spring Boot and Java support dates, jjwt releases) were checked on the web on
2026-10-23; check the exact patch versions on Maven Central when upgrading.
