# O-7: removing the ZK eligibility gate and unsound ZK primitives

**Status:** decided and implemented. **Date:** 2026-10-21.

## What was removed and why

| Removed | Stated use | Why it was unsound | Evidence |
|---|---|---|---|
| `reputation/ZkEligibilityGate` and the PBFT gate (`setZkEligibilityRequired`, `submitEligibilityProof`, `isZkEligible`, `revokeEligibility`, the leader-selection branch) | Prove a validator's reputation ≥ bar without revealing it | The prover chooses the commitment, so the proof says nothing about the validator's real reputation. Eligibility was also stored per node, not agreed by consensus, so enabling it could make nodes elect different leaders. | Code review (audit round 2, O-7) |
| `ZKProofSystem.RangeProof`, `createRangeProofData`, `verifyRangeProof` | Committed value is in [min, max], without revealing it | **Not sound:** the final check compared only x-coordinates, and a point and its negative share x, so a commitment to a value **below** min verifies. **Not zero-knowledge:** each bit's blinding factor `ri` was written into the proof in plain text, so anyone could compute `Ci − ri·H` and read every bit of the value. | A temporary test made an honest proof for 5 in [0, 100], negated the commitment (now hiding −5) and ran the unmodified verifier: `honest(+5 in [0,100]) verifies=true \| negated(-5 in [0,100]) verifies=true` |
| `ZKProofSystem.ThresholdProof`, `createThresholdProof`, `verifyThresholdProof` | Committed value ≥ threshold | Built on the range proof above. Its `create(v, t, false)` also returned an object whose `verify()` was hard-coded to `true`. | Code review |
| `ZKProofSystem.FirmwareProof`, `DeviceLifecycleManager.updateFirmware(..., FirmwareProof)`, the TELEMETRY firmware-proof penalty | Firmware integrity proof | A hash commitment that only the device secret can open, so it proves nothing to a validator. The TELEMETRY penalty only ran for commitments set through the removed overload, which no on-chain path used. Verified firmware now means a manufacturer signature (HC-074). | Code review |
| `ZKProofSystem.generateCommitment` / `verifyCommitment` | Private-data commitment | `generate` hashed with the private key and `verify` with the public key, so a genuine commitment could never verify, and the concatenation had no field boundaries. Unused. | Code review |

## What was kept

| Kept | Why |
|---|---|
| `OwnershipProof` (Schnorr, Fiat-Shamir) | Full point comparison (x and y). The challenge binds R, the public key, the DID and the application challenge. For telemetry and private transfers that challenge is bound to the transaction (HC-071, HC-072). |
| `EqualityProof` | Schnorr proof of knowledge of `r1 − r2` for `C1 − C2` on base H. Full point comparison, and the challenge binds C1, C2 and R. |
| Pedersen commitments (`createCommitment`, `H()`) | H is derived by hashing a public tag onto the curve, so nobody knows its discrete log with respect to G. |

"Kept" means reviewed in this pass, not formally proven or externally audited.

## Consensus impact

None. The gate was off by default, and its eligibility map was node-local. No transaction type used the removed proofs. The removed TELEMETRY branch could only run for firmware commitments created through the removed Java-only overload.

## Tests changed

The tests that exercised the removed APIs were deleted or trimmed: `ZkLeaderEligibilityTest`, `ZkEligibilityGateTest`, the range and threshold tests in `ZKProofSystemTest` and `ZKProofSystemCompleteTest`, the four gate tests in `GovernanceMultiSigZkPentest`, and the FirmwareProof step of `DeviceActivationTest`. `UnsoundZkRemovedTest` asserts the removal. `EdgeReadinessProbe` now benchmarks the ownership proof.
