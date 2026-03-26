# HybridChain V1.0 Bug Fixes Report

This document outlines the critical bugs identified and resolved during the HybridChain stabilization phase.

## 1. SSTORE Opcode Argument Swap
**Issue:** The `SSTORE` opcode execution in `Interpreter.java` popped the value before the key due to stack evaluation order (`value` is on top of `key`), which resulted in the map using the value as the key and the key as the value.
**Fix:** Modified the `.pop()` order to extract the value first, then the key, correctly assigning them to the storage map.
**Impact:** Smart contract storage persistence works correctly.

## 2. Unwired Components in Blockchain Core
**Issue:** `App.java` instantiated `EventBus`, `BlockchainMonitor`, and `AuditLogger` but never passed them to the `Blockchain` instance, leading to `NullPointerException`s when contracts emitted events or blocks were validated.
**Fix:** Added explicit `blockchain.setEventBus()`, `blockchain.setMonitor()`, and `blockchain.setAuditLogger()` calls during node initialization.
**Impact:** Telemetry, metrics, and contract events function without crashing the node.

## 3. Security Config Filter Whitelisting Precedence
**Issue:** The `/api/v1/account/create` endpoint was protected by JWT because the `.anyRequest().authenticated()` fallback matched before the `.permitAll()`. Additionally, `/api/v1/admin/status` was accidentally exposed due to a wildcard `GET /api/**` matcher taking precedence over the `/api/v1/admin/**` rule.
**Fix:** Properly ordered the request matchers in `SecurityConfig.java`, prioritizing explicit `/admin/**` authenticated rules before wildcards, and whitelisting the account creation endpoint explicitly before the fallback.
**Impact:** Restored RBAC, ensuring administrative endpoints are protected while allowing initial account registration safely.

## 4. Block Proposer Shutdown Hang
**Issue:** `App.java` did not gracefully terminate the `ScheduledExecutorService` block proposer, causing the JVM to hang during shutdown signals (SIGTERM).
**Fix:** Registered a JVM shutdown hook that calls `scheduler.shutdown()` and `scheduler.awaitTermination()`.
**Impact:** Docker containers and services can terminate the node cleanly without orphan threads.

## 5. TokenRegistry Race Conditions
**Issue:** The `transferToken` method in `TokenRegistry.java` was not thread-safe. Concurrent transactions involving the same multi-token asset could result in double-spending or incorrect balances.
**Fix:** Applied the `synchronized` keyword to `transferToken`.
**Impact:** Prevented concurrent double-spend attacks in the token registry.

## 6. TELEMETRY Device Validation Failure
**Issue:** `Blockchain.validateTransaction()` expected `tx.getFrom()` to be the `DeviceId` for telemetry transactions. However, clients submitted transactions using their Owner Address, causing `getDeviceRecord` to throw exceptions.
**Fix:** Updated the validation logic to search for registered devices by the owner's address if a direct device ID lookup fails.
**Impact:** Allow device owners to submit telemetry transactions seamlessly.

## 7. Metrics Name Discrepancy
**Issue:** `Blockchain.applyBlock()` recorded the `blocks.created` metric instead of `blocks.validated`.
**Fix:** Corrected the metric key string to `blocks.validated`.
**Impact:** Accurate Prometheus dashboard integration.

## 8. System.out.println Remnants
**Issue:** Several classes used `System.out.println` and `System.err.println` which bypassed structured logging (MDC) and polluted standard output.
**Fix:** Replaced all standard output calls with `slf4j` `log.info`, `log.debug`, and `log.error`.
**Impact:** Consistent log formatting suitable for Logstash/Kibana ingestion.

## 9. Spring Boot Parameter Name Reflection
**Issue:** Upgrading to Spring Boot 3.2.x with Java 17 caused explicit reflection exceptions on `@PathVariable` and `@RequestParam` properties because `-parameters` was missing from the compiler arguments.
**Fix:** Added specific parameter name arguments (`@PathVariable("hash")`, `@RequestParam(value="limit")`) directly to the method signatures in `IoTRestAPI.java`.
**Impact:** Endpoints like `/explorer/block/{hash}` and `/address/...` process requests correctly instead of throwing HTTP 500 errors.

## 10. Sensitive Credentials Tracked in Git
**Issue:** The `.env.20nodes` file containing production private keys and `node_modules` were tracked in VC.
**Fix:** Updated `.gitignore` and removed the files from the git index.
**Impact:** Improved repository hygiene and security posture.
