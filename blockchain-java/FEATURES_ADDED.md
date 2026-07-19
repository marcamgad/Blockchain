# HybridChain V1.0 Features Report

This document highlights the enterprise-grade features and operational enhancements added to HybridChain during the stabilization phase.

## 1. Deterministic Fork Resolution
Implemented a robust tie-breaking mechanism in `Blockchain.java` (`applyBlock`) and `ForkResolution.java`. When two valid blocks present at the same chain height, the implementation resolves the conflict deterministically using the hash value of the proposing validator's public key identifier. This eliminates network partitions and split-brain scenarios entirely under normal operating conditions.

## 2. Prometheus Metrics Integration
Added the `PrometheusBridge.java` utility and an `IoTRestAPI` endpoint at `GET /actuator/prometheus` and `GET /metrics` to export `BlockchainMonitor` metrics natively. Grafana and other monitoring systems can now scrape node health, block creation rates, skipped slots, and transaction pool sizes without third-party exporters.

## 3. Administrative REST Endpoints
Exposed a comprehensive set of RESTful endpoints under `/api/v1/admin/` secured by JWT authentication for dynamic operations.
- `POST /api/v1/admin/node/pause` and `POST /api/v1/admin/node/resume`: Start and stop chain synchronization, mempool clearing, and consensus loops gracefully without killing the container.
- `GET /api/v1/admin/status`: Fetch deep metrics of the mempool size, peer count, node difficulty, and operational paused state.
- `GET /api/v1/admin/peers`, `DELETE /api/v1/admin/peers/{id}`: View connected peers and disconnect specific malicious peers directly.
- `POST /api/v1/admin/broadcast-block`: Manually force a block broadcast for recovery scenarios.
- `POST /api/v1/admin/config/update`: Modify `MAX_TRANSACTIONS_PER_BLOCK` and `TARGET_BLOCK_TIME_MS` on the fly.

## 4. Device Reputation Scoring Engine
Introduced `ReputationEngine.java` as a trust-scoring component for IoT edge devices. It
computes and persists a per-device score: anomaly-free telemetry raises it
(`SUCCESS_INCREMENT`), anomalous telemetry lowers it (`FAILURE_DECREMENT`), inactivity
applies a decay penalty, and `slashToMin()` floors it. Scores are updated from the
TELEMETRY apply path via `DeviceLifecycleManager.recordDeviceActivity()` and persisted to
storage.

> **Scope / limitation (accurate as of this revision):** the reputation score is
> *recorded and readable, but not yet enforced*. There is no code that bans, disconnects,
> rate-limits, or de-peers a device based on its score — `ReputationEngine` is invoked
> only by `DeviceLifecycleManager` to read/write the value. Automatic ban enforcement via
> the peer network is **not implemented**; treat the score as telemetry for operators and
> for the ZK eligibility gate, not as an active security control.

## 5. WebSocket Event Streaming
Implemented a high-performance `EventBus.java` subsystem bound to an active WebSocket connection handler (`EventBusWebSocketHandler.java`). Web clients and dApps can subscribe to real-time asynchronous data streams via `ws://{node}/api/v1/ws/events`.
- Topics include: `blocks`, `transactions`, `mempool`, and `contracts`.
- Dynamic filtering is supported (e.g., subscribing only to `ContractEvent`s for a specific `contractAddress`).
- Includes automatic session reaping for disconnected clients to prevent memory leaks.

## 6. Structured Logging & Correlation
Refactored the logging implementation away from standard output streams towards SLF4J and Logback configurations. Added an `MDCCorrelationInterceptor.java` that automatically injects an `X-Correlation-ID` into every HTTP API request lifecycle. This tracking UUID is present in all Node system logs, allowing deep-dive tracing of a single transaction across consensus boundary logs effectively.

## 7. Chain Explorer REST Endpoints
Augmented `IoTRestAPI.java` with a suite of blockchain index and exploration capabilities.
- `GET /api/v1/explorer/block/{hash}`: Lookup a block globally by its SHA-256 hash representation.
- `GET /api/v1/explorer/tx/{txid}`: Fetch any committed or mempool transaction by its transaction ID.
- `GET /api/v1/explorer/address/{address}`: Index tracking endpoint that grabs the last 50 transactions belonging to an address for rapid balance resolution.
