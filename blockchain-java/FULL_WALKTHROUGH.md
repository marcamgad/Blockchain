# Full Walkthrough: Quick Start Script for IoT Blockchain

## Step 0: Preparing the Environment
**Before running the script:**

**Requirements:**
*   Ubuntu 22.04 (host or VM)
*   Docker & Docker Compose installed
*   `blockchain-java` project cloned
*   Optional: `jq`, `curl` for API queries

**Script Check:**
The script `QUICK_START_TEST.sh` is executable:
```bash
chmod +x QUICK_START_TEST.sh
```

**Outcome:** You’re ready to run a full local 3-node network simulation.

---

## Step 1: Installing Docker and Dependencies
The script prints instructions for installing:
```bash
sudo apt-get update
sudo apt-get install -y docker.io docker-compose jq curl
sudo usermod -aG docker $USER
```

**Behind the scenes:**
*   `docker.io` installs Docker Engine.
*   `docker-compose` manages multi-container setups.
*   `jq` parses JSON API responses.
*   `curl` is used for HTTP requests to blockchain REST APIs.

**Important:** After adding the user to the docker group, you need to log out and log back in (or run `newgrp docker`) to activate permissions, OR use `sudo` for all docker commands (which the script now handles automatically).

---

## Step 2: Verify Docker Installation
The script checks:
```bash
docker --version
docker-compose --version
```
**Outcome:** Confirms Docker is installed and available.

---

## Step 3: Build Docker Images
**Command:**
```bash
sudo docker-compose build
```

**What happens:**
1.  Docker reads `docker-compose.yml`.
2.  Each service (`seed-node`, `peer-node-1`, `peer-node-2`) is built:
    *   **Build stage:** Maven compiles Java code into a fat jar.
    *   **Run stage:** Base JDK image receives the jar, installs dependencies like `libstdc++`.
3.  Docker layers are cached, so repeated builds are faster.

**Outcome:** Three ready-to-run Docker images.

---

## Step 4: Start the Blockchain Network
**Command:**
```bash
sudo docker-compose up -d
```

**Behind the scenes:**
1.  Docker starts containers detached (`-d`).
2.  Each node initializes:
    *   **Seed node** becomes the first blockchain node.
    *   **Peer nodes** connect to the seed node.
    *   Nodes initialize storage (LevelDB/SQL).
    *   Mempool and consensus engine (PBFT) start.

**Outcome:** Three running containers with network connectivity.

---

## Step 5: Wait for Node Initialization
Script waits 60 seconds (`sleep 60`).

**What happens:**
*   Nodes perform peer discovery and handshake.
*   PBFT consensus starts: Seed node acts as leader.
*   Each node initializes blockchain state, UTXO/account tables, and device registries.

**Outcome:** Network ready to accept transactions.

---

## Step 6: Check Node Status
**Command:**
```bash
sudo docker-compose ps
```

**Outcome:** Lists containers and status (Up, Healthy). Ensures nodes are not crashed.

**Behind the scenes:** Docker healthchecks query `/api/v1/health` every 30s.

---

## Step 7: Test Health Endpoints
Script runs:
```bash
curl -s http://localhost:8000/api/v1/health | jq '.'
curl -s http://localhost:8001/api/v1/health | jq '.'
curl -s http://localhost:8002/api/v1/health | jq '.'
```

**What happens:** REST API queries the node for status (Consensus, Sync, IoT Manager).
**Expected output:** `{"status": "healthy", ...}`

---

## Step 8: Check Network Status
**Command:**
```bash
curl -s http://localhost:8000/api/v1/network/status | jq '.'
```

**Outcome:** Confirms nodes are synchronized (Block Height, Peer Count).

---

## Step 9: Run Basic Transaction Test
**Script:** `./test_transaction.sh`

**What happens:**
1.  Creates accounts.
2.  Sends token transactions.
3.  Processes them through PBFT consensus.
4.  Commits transactions to a block.

**Outcome:** Confirms blockchain core works correctly.

---

## Step 10: Run IoT Transaction Test
**Script:** `./test_iot_transaction.sh`

**Behind the scenes:**
1.  **Sensor** registers on seed node.
2.  **Actuator** registers on peer node.
3.  Sensor sends **IoT data** (temperature, humidity) as a transaction.
4.  Transaction propagates via P2P.
5.  PBFT consensus commits the block.
6.  Peer node **queues actuator command**.
7.  Nodes verify **block height consistency** and IoT data sync.

**Outcome:** Confirms the IoT blockchain network is fully functional.

---

## Step 11: View Logs
**Command:**
```bash
sudo docker-compose logs --tail=20 <node>
```

**What to look for:**
*   `IoT Blockchain Node Starting ...`
*   `Connected to seed node`
*   `Block #X committed`
*   `Transaction executed successfully`

**Final Status:** Nodes are up, healthy, and synchronized.

---

## ✅ Summary
1.  Install Docker → Build images → Start nodes
2.  Wait → Check node health → Verify network
3.  Run basic transactions → Run IoT transactions
4.  View logs → Confirm synchronization

Everything from node initialization → consensus → IoT transaction propagation is handled automatically by the script.
