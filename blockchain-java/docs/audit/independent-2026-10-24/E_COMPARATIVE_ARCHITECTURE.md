# E — Comparative Architecture Report

Research date 2026-10-24. Where the current version of a project could not be confirmed from a
primary source in this session, the text says so; architecture descriptions for those projects
come from their long-standing published designs.

## 1. Categories

| Category | Projects | Why |
|---|---|---|
| **A — Direct competitors** | Hyperledger **Fabric** 3.x (SmartBFT); **Besu** (QBFT); **CometBFT** + Cosmos SDK (as a framework for a permissioned app chain); and the non-blockchain baseline of **signed events + a transparency log** (Trillian Tessera) plus a database | Permissioned, multi-party, BFT or append-only records, the same deployment model HybridChain targets |
| **B — Components to reuse or integrate** | CometBFT (consensus engine via ABCI); Tessera / C2SP tlog-tiles (verifiable logs); OpenZeppelin Contracts (if an EVM path is ever chosen); Chainlink (oracle patterns, if public-chain anchoring is wanted) | Each solves a sub-problem with far more review than a custom implementation could get |
| **C — Reference implementations** | Bitcoin Core (UTXO validation, consensus-critical code discipline); Geth (state trie, sync); Corda (point-to-point privacy, notaries); Polkadot SDK (runtime upgrades); Bittensor, Akash (decentralised AI/compute incentives) | Worth studying for specific designs; different trust models or markets |

Bitcoin Core, Geth, Bittensor and Akash are **not** competitors for HybridChain's permissioned IoT
niche. Ranking them against it would be meaningless.

## 2. Direct competitors in detail

### 2.1 Hyperledger Fabric 3.x

- **Status:** Fabric 3.0 reached general availability in September 2024, and was the first blockchain platform to use the SmartBFT protocol for its ordering service; releases v3.1.x exist. *[doc: LF Decentralized Trust announcement; Fabric docs "What's new"]*
- **Model:** execute–order–validate; chaincode in Go, Java or JavaScript; channels and private data collections for confidentiality; MSP/X.509 identities per organisation; endorsement policies.
- **Fault tolerance:** ordering is BFT with SmartBFT (tolerates up to, not including, a third Byzantine orderers); Raft (CFT) remains available.

| Capability | HybridChain | Fabric | Gap | Action |
|---|---|---|---|---|
| BFT ordering, multi-machine production use | single-host tests only | SmartBFT, production deployments | **severe** | Multi-machine evidence (H 3.x) |
| Identity per organisation | one shared CA seed for all | MSP per org, separate CAs | **severe** | Per-operator CAs (C-16) |
| Confidentiality | none on-chain (PRIVATE_TRANSFER disabled) | channels, private data | **severe** | Don't compete; keep data off-chain |
| Device lifecycle and device-signed telemetry | **built in** | would be chaincode | HybridChain ahead **in packaging** | This is the differentiator; prove it saves effort |
| Throughput | ≈ 4.6 tx/s ceiling (F) | far higher in published benchmarks (workload-dependent; figures **not verified** in this session, so don't quote them until measured side by side, F §4) | **severe** | Measure, then fix (H 4.x) |
| Operations and tooling | Docker scripts | Helm charts, operators, CLI, monitoring | **severe** | |
| Licence | **Non-Commercial Source-Available** (`LICENSE`): no commercial or business use, no public distribution of modified versions | Apache-2.0 (OSI open source) | **decisive** for adoption | Choose deliberately: an OSI licence (Apache-2.0) for adoption, or keep it and offer commercial licences, knowing it rules out community contribution |

### 2.2 Besu (QBFT)

- **Status:** part of LF Decentralized Trust (since September 2024, now named "Besu"); supports IBFT 2.0, QBFT and Clique; QBFT gives immediate finality. A July 2025 newsletter reported release 25.7.0; the current 2026 version wasn't confirmed in this session. *[doc]*
- **Model:** a full Ethereum client (EVM, JSON-RPC, Solidity tooling, permissioning).

| Capability | HybridChain | Besu | Gap |
|---|---|---|---|
| Contract ecosystem | custom VM + WASM; no developer tooling | EVM + Solidity + OpenZeppelin + every Ethereum tool | **severe**: do not compete on contracts |
| Language | Java | Java | none (Besu is a reference for JVM performance work) |
| Device trust | built in | contracts would have to implement it | HybridChain ahead in packaging |

### 2.3 CometBFT + Cosmos SDK

- **Status:** CometBFT v0.39.3 (May 2026) is current in the 2026.1 release family together with Cosmos SDK 0.54.x; a consensus-level BFT-time fix shipped in January 2026 (v0.38.21/v0.37.18). *[doc: CometBFT changelog, Cosmos release family]*
- **Relevant design facts:**
  - validators persist their last signed (height, round, step) in `priv_validator_state.json` to prevent double signing across restarts, which is exactly HybridChain's C-1;
  - a `double_sign_check_height` option guards restarts;
  - the spec states CometBFT doesn't handle evidence of conflicting proposals (parity with C-4);
  - proposer-based timestamps (PBTS) bound block time against validator clocks (the remedy pattern for C-10).

  *[doc: CometBFT signing spec, priv_validator_state reference, PBTS docs]*

| Capability | HybridChain | CometBFT | Gap |
|---|---|---|---|
| Restart-safe signing | none (C-1) | persisted last-sign state | **severe** |
| BFT time | none (C-10) | PBTS | medium |
| Light clients, state sync | no | yes | severe |
| Years of production and audits | none | many chains, published advisories | severe |

**Strategic reading:** a defensible path is to keep HybridChain's **application layer** (device
lifecycle, readings, sensor state) and run it on **CometBFT via ABCI**, instead of maintaining a
custom BFT engine. The cost: a rewrite of the networking and consensus layers around ABCI (Java
ABCI servers exist, but would need evaluation). The benefit: consensus correctness inherited from
a heavily reviewed engine.

### 2.4 The non-blockchain baseline: signed events + transparency log

- Trillian is in maintenance mode, and its successor **Tessera** reached v1.0 general availability (C2SP tlog-tiles). *[doc: transparency.dev blog]*
- Amazon QLDB, the best-known managed "ledger database", reached end of support on 31 July 2025, with AWS recommending Aurora PostgreSQL. *[doc]*

For **a single operator** (or a consortium that accepts one log operator plus public
verifiability), device-signed events stored in a database and appended to a verifiable log give
tamper evidence, inclusion and consistency proofs, high throughput and simple operations, **without
BFT consensus**. HybridChain is justified only where several mutually distrusting operators must
jointly *decide* state transitions (who owns a device, whether it was revoked before a reading),
not just record them.

## 3. Category B and C notes

| Project | Status found | What to take | What not to do |
|---|---|---|---|
| OpenZeppelin Contracts | 5.x line, v5.6.x stable, 5.7 in release candidate *[doc: release trackers]* | Access-control and upgradeability patterns if an EVM path is chosen | Re-implement token standards in a custom VM |
| Chainlink | current status **not re-verified** this session | Oracle design (multiple signers, aggregation) for sensor data | Build a general oracle network |
| Polkadot SDK | GPL-3.0 for the node, Substrate GPL-3.0/Apache-2.0 *[doc]* | Forkless runtime upgrades (pattern for activation heights) | Become a parachain without a reason |
| Corda | Open Source Edition 4.14, Apache-2.0 (notices dated March 2026); Corda 5 status not confirmed *[doc]* | Point-to-point data sharing, notary model for privacy | — |
| Bitcoin Core | current version **not re-verified** | Consensus-critical code isolation and review culture | Compare throughput |
| Geth | current version **not re-verified** | Trie storage by node hash, snapshot sync (relevant to H 4.1) | — |
| Bittensor | dynamic TAO since February 2025; subnet counts reported inconsistently by third parties *[doc, secondary]* | Nothing for the IoT niche | Pivot into an AI incentive network |
| Akash | Q1 2026 report: record compute spend; BME tokenomics live March 2026 *[doc, project-published]* | Nothing for the IoT niche | Build a compute marketplace |

## 4. Bottom line

- **No category-A comparator is beaten on any measured axis.** HybridChain has no measurements to compare.
- **Where HybridChain is ahead:** the IoT trust rules come built into the state machine. Every comparator needs them written as application code.
- **The most credible competitive story** is therefore "device-trust application layer", either on HybridChain's own consensus once it is proven, or on CometBFT.
