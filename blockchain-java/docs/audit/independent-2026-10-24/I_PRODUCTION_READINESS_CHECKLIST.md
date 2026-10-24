# I — Production-Readiness Checklist

✅ met · ⚠️ partially · ❌ not met. Each line names its evidence (B) or finding (C).

## 1. Checklist

### Protocol safety
- ✅ Correct quorum for every n (HC-002)
- ✅ Commit certificates verified on sync (HC-046)
- ✅ One vote per (view, height) within a process (HC-087)
- ❌ Restart-safe signing record (C-1)
- ⚠️ Per-view quorums (C-9)
- ❌ Byzantine proposer and voter tested live (D §3)
- ❌ Formal model of the current protocol (D §4)
- ❌ Multi-machine operation (C-12)

### Cryptography
- ✅ Low-S ECDSA over full digests; point validation; domain tags on every signed structure (HC-040, HC-091)
- ✅ Hybrid `hq` accounts bound to both keys (HC-079)
- ❌ Post-quantum consensus and transport (C-17)
- ❌ Reviewed ZK implementation (C-18)
- ❌ Key provider / HSM (C-16)

### Networking and API
- ✅ mTLS between validators; bounded handshake; signed messages
- ❌ TLS on the REST and WebSocket API (C-11)
- ❌ Per-operator certificate authorities (C-16)
- ⚠️ Rate limits (present, but after authentication; WebSocket unbounded, C-6)
- ❌ Configurable connection limits behind NAT

### Storage and state
- ✅ Atomic block persistence; inconsistent store refused (HC-089)
- ✅ Canonical state root with a golden test (HC-090)
- ⚠️ Determinism across CPU architectures (C-5)
- ❌ Process-kill crash testing (C-13)
- ❌ Incremental state storage (F §2.3)
- ❌ Backup and restore procedure tested

### Privacy and data
- ❌ Personal-data handling and retention policy
- ⚠️ Differential privacy claims (C-8)

### Operations
- ✅ Non-root container
- ✅ CI on every push; Dependabot
- ❌ Container health check
- ❌ Dashboards and alerts
- ❌ Rolling upgrades tested
- ❌ Validator onboarding and removal (C-14)
- ❌ Incident and key-compromise runbooks tested (`docs/KEY_ROTATION.md` exists, untested)
- ❌ Supported platform versions (C-21)

### Governance and legal
- ❌ Security policy and disclosure channel (C-7)
- ❌ A licence that allows the intended use (C-22)

### Independent validation
- ❌ External security review (C-15)
- ❌ Reproducible benchmarks reviewed by a third party (F)

**Score: 10 ✅, 4 ⚠️ and 24 ❌ out of 38.**

## 2. Classification

| Stage | Status |
|---|---|
| Research demo | ✅ ready |
| Development testnet (single host) | ✅ ready |
| Controlled pilot | ❌ needs Phases 1–3 of H plus TLS and per-operator CAs |
| Production | ❌ |

## 3. Failure matrix

| Scenario | Expected behaviour | Detection | Recovery | Safety | Data loss | Automation needed | Evidence today |
|---|---|---|---|---|---|---|---|
| Validator process crash | others continue if ≥ q remain | peer count, missing votes | restart; sync with certificates | **at risk after restart (C-1)** | none | supervisor restart | live harness leader-crash (pre-fix) |
| Machine loss | as above; replacement node syncs | health check | new node from genesis or checkpoint | as above | none if quorum survives | provisioning script | none |
| Disk failure / corruption | node refuses inconsistent store (HC-089) | start-up error | wipe and resync | protected | local only | resync script | unit test |
| Network partition | no progress on the minority side; resumes on heal | height stall | automatic (view change, sync) | holds in tests | none | — | live harness (single host) |
| Validator key compromise | attacker can double-sign → jailed; can't forge others | evidence on-chain | rotate key: **impossible without a network restart** (C-14) | degraded | none | rotation procedure | none |
| Quorum unavailable | chain stops; back-off up to 1,080 s | height stall alert | restore validators | holds | none | alerting | live harness |
| Invalid upgrade | nodes on different rules split | state-root mismatch logs | roll back the binary | depends on activation discipline | possible | release process | none |
| Corrupted state on disk | refused at start-up | start-up error | resync | protected | local only | — | unit test |
| Operator mistake (wrong config, shared key reused) | some mistakes refused (compromised keys, HC-052) | start-up checks | fix config | partly protected | — | config validation | unit tests |
| Lying sync peer | lagging node stuck (C-2) | none | manual peer removal | holds | none | peer rotation | none |
