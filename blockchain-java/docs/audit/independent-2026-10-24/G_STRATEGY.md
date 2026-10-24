# G — Strategic Product Recommendation

## 1. The five directions

Scores are 1–10; **higher is better** in every column, so a high "security burden" score means a
*light* burden and a high "integration complexity" score means *simple* integration.

| Direction | Technical fit | Solo feasibility | Differentiation | Security burden (inverse) | Integration (inverse) | Demonstrability | Career value | **Total /70** |
|---|---|---|---|---|---|---|---|---|
| 1. Permissioned finance / tokenised assets | 4 | 2 | 2 | 1 | 2 | 4 | 6 | **21** |
| 2. **Industrial IoT, device-attested records** | **8** | **6** | **6** | **5** | **5** | **8** | **8** | **46** |
| 3. AI-assisted chain security / verifiable risk monitoring | 4 | 5 | 4 | 6 | 5 | 6 | 7 | **37** |
| 4. Supply-chain finance and multi-party settlement | 6 | 3 | 4 | 3 | 3 | 5 | 6 | **30** |
| 5. General cryptocurrency / DeFi | 2 | 1 | 1 | 1 | 2 | 3 | 4 | **14** |

### Why

1. **Finance and tokenised assets.** The buyers need audited, licensed, regulated stacks (Besu/QBFT, Fabric, Corda, Canton), legal finality and custody. HybridChain has no decimals, no privacy, no audit, and a non-commercial licence. Its token model is incidental, not a strength.
2. **Industrial IoT, device-attested records.** This is the only area where HybridChain has something the comparators lack *out of the box*: manufacturer-attested provisioning, activation that needs the device's consent, owner-only lifecycle changes, device-signed readings checked against block time, and the latest authenticated reading exposed deterministically to contracts. It can be demonstrated end to end on a Raspberry Pi with a few sensors. Its weak point is that a single-operator deployment doesn't need a blockchain (E §2.4), so the use case must involve **several mutually distrusting operators**.
3. **AI-assisted security monitoring.** The AI modules are advisory and unevaluated (no datasets, no precision/recall, differential privacy without accounting). Comparators here are SIEM and analytics products, not ledgers; a ledger adds little. It's worth a **complement** only: anomaly flags over authenticated readings, measured against simple statistical baselines.
4. **Supply-chain finance.** Real, but it needs settlement, privacy between competitors and bank integration: Corda/Fabric territory, with a heavy legal and integration burden.
5. **Cryptocurrency / DeFi.** Requires public, permissionless security, economic attack resistance, wallets, exchanges and audits. None of it exists, and the competitors are among the most hardened software in the world. **Do not pursue.**

## 2. Recommendation

**Primary:** *multi-operator, device-attested records for regulated or contested equipment data.*
Example scope: **cold-chain temperature evidence shared by shipper, carrier and receiver**, or
**calibration and firmware history of regulated devices shared by manufacturer, operator and
auditor**. In both, several parties dispute what happened, and no single one is trusted to keep
the record.

**Complementary:** an *advisory anomaly layer* on authenticated readings (direction 3, narrowed),
evaluated against z-score and threshold baselines with precision, recall and false-positive rate.
It must never feed back into validity.

**Defer or drop:**
- tokens and economics beyond fees;
- private transfers;
- federated learning (until there's a dataset and a threat model);
- the custom ZK proofs;
- general smart contracts beyond the threshold and alert rules the application needs;
- the `LIGHT` role;
- governance (until validator-set changes are designed);
- any cryptocurrency or DeFi positioning.

## 3. The smallest credible application

**"Cold-chain evidence ledger" (pilot scope):**

- **Parties:** 3 operators (shipper, carrier, receiver) plus 1 neutral validator (auditor), so n = 4 validators on 4 machines, each operator holding its own CA and keys.
- **Devices:** 10–50 temperature loggers (Raspberry Pi or ESP32 plus a sensor) with manufacturer-provisioned keys, sending signed readings every 60 s through a gateway.
- **On-ledger rules:** device lifecycle; custody transfer per shipment; readings accepted only from active devices in current custody; and a deterministic threshold contract that records a **breach event** when readings exceed limits for N consecutive minutes.
- **Off-ledger:** raw data in each party's database; the ledger stores readings or their hashes, plus breach events.
- **Interfaces:** a REST API over TLS, an SDK for gateway firmware, and a dispute report (for a shipment: every reading, signer, custody state, breach) verifiable by any party.

**Acceptance criteria:**
1. Runs 30 days on 4 machines operated by different people.
2. Survives the fault matrix (I §3) without divergence.
3. A dispute report for any shipment can be checked independently.
4. Measured throughput covers 10× the pilot's device load.
5. Compared against (a) Fabric with equivalent chaincode and (b) a database + Tessera log baseline, on integration effort and correctness under faults (F §4).

## 4. What would make this direction wrong

If a pilot partner is happy with a single trusted operator, the baseline (database + signatures +
transparency log) wins, and HybridChain should become a library of device-trust rules. If the
Fabric comparison shows equal effort and better operations, build the device-trust layer as Fabric
chaincode, or as a CometBFT application.
