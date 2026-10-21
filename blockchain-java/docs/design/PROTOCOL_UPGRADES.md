# Design: protocol upgrades with activation heights

**Status:** design only, not implemented. **Date:** 2026-10-21.

## Problem

Every consensus rule change so far (PBFT vote encoding v2, checkpoint hash v2, commit certificates, `EQUIVOCATION_EVIDENCE`, hybrid `hq` addresses, sensor readings in the state root, disabling `PRIVATE_TRANSFER`) took effect the moment a node ran the new code. The consequences:

- All validators must switch at the same instant, usually from empty storage.
- A node on the new code cannot replay an old chain whose blocks were valid under the old rules.
- Mixed versions disagree silently. The only guard is the handshake rule-flag check (HC-008), which covers one setting, `REQUIRE_QUANTUM_SIG`.

The current stop-gaps are ad-hoc constants (`HYBRID_ADDRESS_ACTIVATION_HEIGHT`, `PRIVATE_TRANSFER_DISABLED`).

## Options

| Option | How it works | For | Against |
|---|---|---|---|
| **A. Static fork schedule in code** | A `ForkSchedule` maps height ranges to a `Rules` object (`rules(height).privateTransferEnabled()`, `.voteEncoding()`, …). Each release ships the full schedule. Blocks carry a `protocolVersion` field that must equal `rules(height).version()`. | Simple and deterministic. Old blocks replay under old rules. This is how Ethereum's named forks work. | Activation heights are chosen at release time, so a delay needs a new release. |
| **B. On-chain activation by validator vote** | A `PROTOCOL_UPGRADE` transaction proposes (version, height). It activates once a quorum of validators has signed for it, and is recorded in state. | No release needed to reschedule. Auditable. | Code for every version must already be in the binary. Adds governance attack surface. |
| **C. Version-bit signalling (BIP 9 style)** | Proposers set version bits, and a rule locks in after N of M blocks signal. | Measures actual readiness. | Built for permissionless proof-of-work. With a fixed validator set, (B) gives the same information directly. |

## Recommendation

**A now, B later.**

1. Add `ForkSchedule` and `Rules`. Entry 0 at height 0 is "the rules as of the commit that introduces this", so existing chains replay unchanged.
2. Route every existing switch through `rules(height)`: `HYBRID_ADDRESS_ACTIVATION_HEIGHT`, `PRIVATE_TRANSFER_DISABLED`, the sensor-readings root extension, vote and checkpoint encodings. Validation and apply code already receive the block height.
3. Add `protocolVersion` to the block header, inside the canonical encoding. Reject blocks whose version differs from `rules(index).version()`, and blocks from an unknown future version.
4. Exchange `hash(ForkSchedule)` in the P2P handshake (extending `ruleFlags`). Refuse peers with a different schedule, so mismatches are loud.
5. Later: a `PROTOCOL_UPGRADE` transaction (option B) that can only **set** the height of a version the binary already contains.

## Migration cost

About 2–3 weeks.

- The block header change is itself a fork, so it needs one last coordinated switch. That is the reason to do it now, while there is no production chain.
- Touches `Block` (encoding), `Blockchain.validateTransaction`, `applyTransactionToState`, `PBFTConsensus` (encodings), `PeerNode` (handshake) and `Checkpoint`.
- Tests:
  - replay a chain across a fork height;
  - mixed-schedule peers are refused;
  - a block with the wrong version is rejected.
