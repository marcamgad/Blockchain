# Changelog - HybridChain

All notable changes to this project will be documented in this file.

## [2.0.0] - 2026-03-16

### Added
- **Multi-Token Support**: Native support for custom tokens via `TOKEN_REGISTER`, `TOKEN_MINT`, `TOKEN_BURN`, and `TOKEN_TRANSFER` transaction types.
- **Smart Contract Events**: Implementation of the `LOG` opcode in the Interpreter and full integration of `ContractEvent` into `TransactionReceipt`.
- **Expanded Instruction Set**: Added `DUP`, `SWAP`, `EQ`, `LT`, `GT`, `MOD`, `VALUE`, and `SELFBALANCE` opcodes to the IoT-hardened VM.
- **Integration Tests**: New `MultiTokenTest.java` and `IntegrationTest.java` for comprehensive lifecycle and wiring verification.

### Changed
- **Performance Optimization**: Transitioned total supply tracking (tokenomics) from O(N) chain scanning to a persisted O(1) field in `Blockchain.java`.
- **Fee Market Hardening**: Improved `FeeMarket` persistence, ensures the base fee is correctly loaded from storage on node restart.
- **API Cleanup**: Refactored `IoTRestAPI.java` to remove legacy dependencies and unused fields (`identityManager`).
- **Telemetry Hash Logic**: Automatic hashing of telemetry payloads exceeding 1024 bytes to conserve storage space.

### Fixed
- **State Root Mismatches**: Resolved issues in transaction simulation during mempool reprocessing.
- **Receipt Capture**: Corrected the flow for capturing contract-emitted events during block application.

## [1.5.0] - 2026-02-28
- Initial IoT-hardened VM (Simplified version).
- Base P2P networking and PoA consensus.
- Core UTXO and Account state models.
