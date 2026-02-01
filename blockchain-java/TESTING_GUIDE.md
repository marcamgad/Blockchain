# Quick Start Testing Guide

Follow these steps to test your Docker deployment.

## Prerequisites Installation

Since Docker is not yet installed, run these commands first:

```bash
# Update package list
sudo apt-get update

# Install Docker and dependencies
sudo apt-get install -y docker.io docker-compose jq curl

# Add your user to docker group (to run without sudo)
sudo usermod -aG docker $USER

# IMPORTANT: Logout and login again for group changes to take effect
# Or run: newgrp docker
```

Verify installation:
```bash
docker --version
docker-compose --version
```

---

## Option 1: Automated Testing (Recommended)

Run the automated test script:

```bash
cd ~/Blockchain/blockchain-java
./QUICK_START_TEST.sh
```

This will:
1. ✅ Verify Docker installation
2. ✅ Build Docker images
3. ✅ Start 3-node network
4. ✅ Wait for initialization
5. ✅ Test health endpoints
6. ✅ Run transaction tests
7. ✅ Run IoT transaction tests
8. ✅ Show logs

---

## Option 2: Manual Step-by-Step

### Step 1: Build Docker Images

```bash
cd ~/Blockchain/blockchain-java
docker-compose build
```

**Expected**: Takes 3-5 minutes on first build. You should see:
```
Successfully built abc123def456
Successfully tagged blockchain-java-seed-node:latest
```

### Step 2: Start the Network

```bash
docker-compose up -d
```

**Expected output**:
```
Creating network "blockchain-java_blockchain-net" ... done
Creating volume "blockchain-java_seed_data" ... done
Creating volume "blockchain-java_peer1_data" ... done
Creating volume "blockchain-java_peer2_data" ... done
Creating blockchain-seed ... done
Creating blockchain-peer-1 ... done
Creating blockchain-peer-2 ... done
```

### Step 3: Check Container Status

```bash
docker-compose ps
```

**Expected**: All containers should show "Up" status:
```
NAME               STATUS              PORTS
blockchain-seed    Up (healthy)        0.0.0.0:8000->8000/tcp, 0.0.0.0:6001->6001/tcp
blockchain-peer-1  Up (healthy)        0.0.0.0:8001->8000/tcp, 0.0.0.0:6002->6001/tcp
blockchain-peer-2  Up (healthy)        0.0.0.0:8002->8000/tcp, 0.0.0.0:6003->6001/tcp
```

### Step 4: Test Health Endpoints

```bash
# Seed node
curl http://localhost:8000/health | jq '.'

# Peer node 1
curl http://localhost:8001/health | jq '.'

# Peer node 2
curl http://localhost:8002/health | jq '.'
```

**Expected output** (for each):
```json
{
  "status": "healthy",
  "nodeId": "seed-node",
  "timestamp": 1738414508000
}
```

### Step 5: Check Network Status

```bash
curl http://localhost:8000/api/v1/network/status | jq '.'
```

**Expected output**:
```json
{
  "nodeId": "seed-node",
  "nodeName": "SeedNode",
  "isSeed": true,
  "blockHeight": 0,
  "networkId": 101,
  "protocolVersion": "1.0.0",
  "validatorCount": 0,
  "validators": []
}
```

### Step 6: View Logs

```bash
# All nodes
docker-compose logs -f

# Or specific node
docker-compose logs -f seed-node
```

**Look for**:
```
========================================
IoT Blockchain Node Starting
========================================
Node ID: seed-node
Is Seed Node: true
API Port: 8000
P2P Port: 6001
...
IoT REST API initialized successfully
```

Press `Ctrl+C` to exit logs.

### Step 7: Run Basic Transaction Test

```bash
./test_transaction.sh
```

**Expected**: Should complete in 30-60 seconds with:
```
========================================
TEST COMPLETED
========================================
✓ Multi-node transaction test finished successfully!
```

### Step 8: Run IoT Transaction Test

```bash
./test_iot_transaction.sh
```

**Expected**: Should complete in 60-90 seconds with:
```
========================================
IoT TEST COMPLETED
========================================
✓ IoT blockchain transaction test finished!
Results saved to: iot_test_results_20260201_134508.json
```

### Step 9: Verify Synchronization

Check that all nodes have the same blockchain height:

```bash
# Seed node
curl -s http://localhost:8000/api/v1/network/status | jq '.blockHeight'

# Peer 1
curl -s http://localhost:8001/api/v1/network/status | jq '.blockHeight'

# Peer 2
curl -s http://localhost:8002/api/v1/network/status | jq '.blockHeight'
```

**All should return the same number** (e.g., `5`)

---

## Troubleshooting

### Containers won't start

```bash
# Check logs for errors
docker-compose logs

# Common issues:
# 1. Ports already in use
sudo lsof -i :8000
sudo lsof -i :8001
sudo lsof -i :8002

# 2. Build failed
docker-compose build --no-cache
```

### Health check fails

```bash
# Wait longer (nodes need 60s to start)
sleep 60
curl http://localhost:8000/health

# Check if Spring Boot started
docker-compose logs seed-node | grep "Started IoTRestAPI"
```

### Tests fail

```bash
# Ensure nodes are healthy first
curl http://localhost:8000/health
curl http://localhost:8001/health
curl http://localhost:8002/health

# Check if jq is installed
jq --version
# If not: sudo apt-get install jq

# Re-run test with verbose output
bash -x ./test_transaction.sh
```

---

## Stopping the Network

```bash
# Stop and preserve data
docker-compose down

# Stop and clear all data
docker-compose down -v
```

---

## Next Steps

Once testing is successful:

1. ✅ **Multi-PC Deployment**: See [MULTI_PC_DEPLOYMENT.md](MULTI_PC_DEPLOYMENT.md)
2. ✅ **Production Setup**: Review security checklist in [DOCKER_README.md](DOCKER_README.md)
3. ✅ **IoT Integration**: Connect real sensors and actuators
4. ✅ **Monitoring**: Set up Grafana/Prometheus
5. ✅ **Backup**: Configure automated backups

---

## Quick Commands Reference

```bash
# Start network
docker-compose up -d

# Stop network
docker-compose down

# View logs
docker-compose logs -f

# Check status
docker-compose ps

# Restart a node
docker-compose restart seed-node

# Rebuild images
docker-compose build --no-cache

# Clean everything
docker-compose down -v
docker system prune -a
```

---

**Ready to start?** Run:
```bash
./QUICK_START_TEST.sh
```

Or follow the manual steps above!
