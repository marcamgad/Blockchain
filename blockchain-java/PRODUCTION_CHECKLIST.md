# Production Readiness Checklist: HybridChain

Before deploying HybridChain to production validator nodes (AWS/GCP/Azure) or running the private 20-node deployment, verify the following configuration and environmental criteria are satisfied.

## 1. Network & System Configuration
- [ ] **Firewall Ports:** Ensure `TCP 8000` (REST API + WebSocket, `API_PORT`), `TCP 6001` (P2P, `P2P_PORT`), and — only on GATEWAY-role nodes — `UDP 5683` (CoAP, `COAP_PORT`) are open between allowed nodes. These are the defaults in `Config.java`; override via the matching environment variables. The WebSocket stream is served on the REST port at `ws://{node}/api/v1/ws/events` — it does **not** need a separate port.
- [ ] **File Descriptors:** Increase `ulimit -n` on Linux nodes to at least `65535` to prevent "Too many open files" errors over prolonged P2P connections.
- [ ] **Docker Compose:** Utilize the updated `/docker/docker-compose-validator.yml` to spin up node deployments with restart-always policies.

## 2. Secrets & Keys Lifecycle
- [ ] **Private Key Seeding:** Never store the `NODE_PRIVATE_KEY` in environment source files (e.g. `.env.20nodes`). Use a secure secrets manager (AWS Secrets Manager, Hashicorp Vault) or provide it via a CI/CD orchestration pipeline directly into the environment payload in-memory.
- [ ] **Genesis State Modification:** For MainNet release, overwrite the `genesis.json` in `src/main/resources/` with the officially signed production multi-sig balances if token distribution changes.
- [ ] **JWT Key Rolling:** Modify the `JWT_SECRET` regularly for Web API endpoint authorization. Make sure all frontend dashboards synchronize with the same backend secret phase.

## 3. Storage & Integrity
- [ ] **Storage Backend Validation:** LevelDB handles the persistent `Storage.java` implementation. Ensure the host maps a permanent SSD-backed container volume to the data directory to avoid severe sync delays when blocks reconstruct.
- [ ] **Disk Space Alerts:** Setup CloudWatch/Datadog monitors targeting the volume allocation. The state tries pruning, but large payloads (Contract Data, Telemetry arrays) will accumulate.

## 4. Auditing & Monitoring
- [ ] **Prometheus Config:** Update the external Prometheus `prometheus.yml` scrape configuration to hit `http://target-node-ip:8080/actuator/prometheus` with a 15-second polling interval.
- [ ] **Grafana Dashboard:** Import the `HybridChain-NodeX-Dashboard.json`. Ensure the `blocks.validated` and `mempool.size` panels are healthy during normal transaction floods.
- [ ] **Adversarial Alerts:** Configure Slack/Email alert thresholds targeting the `ReputationEngine.java` ban penalties or dropped peer exceptions indicating a potential network isolation attack.

## 5. Performance Tuning
- [ ] **Rate Limiting:** Modify the `transactionSubmitLimiter` token bucket in `IoTRestAPI.java` if legitimate IoT sensor deployments exceed 40 txs/minute consistently.
- [ ] **Consensus Timeout Params:** Inspect `PBFTConsensus.java` timeout configs. In high-latency geolocated nodes, `ViewChangeTimeout` might require augmentation to prevent constant view shifts.
- [ ] **JVM Memory Settings:** Ensure `JAVA_OPTS` provides adequate heap via `-Xms2G -Xmx4G` depending on block sizes and mempool retention guarantees.
