# Multi-PC Deployment Guide

Complete guide for deploying the IoT blockchain across **two physical PCs** with real network communication.

## Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [PC1: Seed Node Setup](#pc1-seed-node-setup)
- [PC2: Peer Node Setup](#pc2-peer-node-setup)
- [Network Configuration](#network-configuration)
- [Testing IoT Transactions](#testing-iot-transactions)
- [Troubleshooting](#troubleshooting)

---

## Overview

This guide walks you through deploying a **two-node IoT blockchain network** across two physical computers:

- **PC1**: Seed node (bootstrap node)
- **PC2**: Peer node (connects to PC1)

```
┌─────────────────────────┐         ┌─────────────────────────┐
│   PC1 (Seed Node)       │         │   PC2 (Peer Node)       │
│   IP: 192.168.1.10      │◄───────►│   IP: 192.168.1.20      │
│                         │  P2P    │                         │
│  ┌──────────────────┐   │  6001   │  ┌──────────────────┐   │
│  │  Blockchain Node │   │         │  │  Blockchain Node │   │
│  │  API: 8000       │   │         │  │  API: 8000       │   │
│  │  P2P: 6001       │   │         │  │  P2P: 6001       │   │
│  └──────────────────┘   │         │  └──────────────────┘   │
└─────────────────────────┘         └─────────────────────────┘
```

---

## Prerequisites

### Both PCs

1. **Ubuntu 20.04+** or similar Linux distribution
2. **Docker 20.10+**
3. **Docker Compose 2.0+**
4. **Network connectivity** between PCs
5. **Static IP addresses** or known hostnames

### Install Docker on Both PCs

```bash
# Update system
sudo apt-get update
sudo apt-get upgrade -y

# Install Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# Install Docker Compose
sudo apt-get install -y docker-compose-plugin

# Add user to docker group
sudo usermod -aG docker $USER

# Logout and login for group changes to take effect
```

### Install Utilities

```bash
sudo apt-get install -y git curl jq net-tools
```

---

## PC1: Seed Node Setup

### Step 1: Clone Repository

```bash
cd ~
git clone <your-repo-url> blockchain
cd blockchain/blockchain-java
```

### Step 2: Get PC1 IP Address

```bash
# Find your IP address
ip addr show | grep "inet " | grep -v 127.0.0.1

# Example output: 192.168.1.10
# Note this IP - you'll need it for PC2
```

### Step 3: Create Seed Node Configuration

Create `docker-compose.seed.yml`:

```bash
cat > docker-compose.seed.yml << 'EOF'
version: '3.8'

services:
  seed-node:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: blockchain-seed
    hostname: seed-node
    environment:
      - NODE_ID=seed-node-pc1
      - NODE_NAME=SeedNode-PC1
      - NODE_PRIVATE_KEY=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2
      - IS_SEED=true
      - API_PORT=8000
      - P2P_PORT=6001
      - STORAGE_PATH=/app/data
      - NETWORK_ID=101
      - PROTOCOL_VERSION=1.0.0
      - STORAGE_AES_KEY=1234567890abcdef
      - JAVA_OPTS=-Xmx2G -Xms512M
    ports:
      - "8000:8000"  # API port
      - "6001:6001"  # P2P port
    volumes:
      - blockchain_data:/app/data
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8000/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s

volumes:
  blockchain_data:
    driver: local
EOF
```

### Step 4: Configure Firewall

```bash
# Allow P2P port (required for peer connection)
sudo ufw allow 6001/tcp

# Allow API port (optional, for remote API access)
sudo ufw allow 8000/tcp

# Enable firewall if not already enabled
sudo ufw enable

# Check status
sudo ufw status
```

### Step 5: Build Docker Image

```bash
# Build the image (takes 3-5 minutes)
docker-compose -f docker-compose.seed.yml build

# Verify image
docker images | grep blockchain
```

### Step 6: Export Docker Image

```bash
# Save image to tar file
docker save blockchain-java-seed-node:latest | gzip > blockchain-node.tar.gz

# Check file size
ls -lh blockchain-node.tar.gz
# Should be ~300-400 MB
```

### Step 7: Transfer Image to PC2

Choose one method:

**Option A: USB Drive**
```bash
# Copy to USB
cp blockchain-node.tar.gz /media/usb/

# On PC2, copy from USB
cp /media/usb/blockchain-node.tar.gz ~/
```

**Option B: SCP (if SSH is enabled)**
```bash
# From PC1, transfer to PC2
scp blockchain-node.tar.gz user@192.168.1.20:~/
```

**Option C: HTTP Server**
```bash
# On PC1, start simple HTTP server
python3 -m http.server 8080

# On PC2, download
wget http://192.168.1.10:8080/blockchain-node.tar.gz
```

### Step 8: Start Seed Node

```bash
# Start the seed node
docker-compose -f docker-compose.seed.yml up -d

# Check logs
docker-compose -f docker-compose.seed.yml logs -f

# Wait for "IoT REST API initialized successfully"
# Press Ctrl+C to exit logs
```

### Step 9: Verify Seed Node

```bash
# Check health
curl http://localhost:8000/health

# Expected output:
# {"status":"healthy","nodeId":"seed-node-pc1","timestamp":...}

# Check network status
curl http://localhost:8000/api/v1/network/status | jq '.'

# Check P2P port is listening
sudo netstat -tlnp | grep 6001
```

---

## PC2: Peer Node Setup

### Step 1: Load Docker Image

```bash
cd ~

# Load the image
docker load < blockchain-node.tar.gz

# Verify image loaded
docker images | grep blockchain
```

### Step 2: Clone Repository (for configs)

```bash
cd ~
git clone <your-repo-url> blockchain
cd blockchain/blockchain-java
```

### Step 3: Create Peer Node Configuration

**Important**: Replace `192.168.1.10` with PC1's actual IP address!

```bash
# Get PC1 IP (you noted this earlier)
PC1_IP="192.168.1.10"  # ← CHANGE THIS

cat > docker-compose.peer.yml << EOF
version: '3.8'

services:
  peer-node:
    image: blockchain-java-seed-node:latest
    container_name: blockchain-peer
    hostname: peer-node
    environment:
      - NODE_ID=peer-node-pc2
      - NODE_NAME=PeerNode-PC2
      - NODE_PRIVATE_KEY=b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3
      - IS_SEED=false
      - SEED_PEER=${PC1_IP}:6001
      - API_PORT=8000
      - P2P_PORT=6001
      - STORAGE_PATH=/app/data
      - NETWORK_ID=101
      - PROTOCOL_VERSION=1.0.0
      - STORAGE_AES_KEY=1234567890abcdef
      - JAVA_OPTS=-Xmx2G -Xms512M
    ports:
      - "8000:8000"  # API port
      - "6001:6001"  # P2P port
    volumes:
      - blockchain_data:/app/data
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8000/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s

volumes:
  blockchain_data:
    driver: local
EOF
```

### Step 4: Configure Firewall

```bash
# Allow P2P port
sudo ufw allow 6001/tcp

# Allow API port
sudo ufw allow 8000/tcp

# Enable firewall
sudo ufw enable

# Check status
sudo ufw status
```

### Step 5: Test Network Connectivity

```bash
# Ping PC1
ping -c 3 192.168.1.10

# Test P2P port connectivity
nc -zv 192.168.1.10 6001

# If connection fails, check:
# 1. PC1 firewall allows port 6001
# 2. Network cables/WiFi connected
# 3. Both PCs on same subnet
```

### Step 6: Start Peer Node

```bash
# Start the peer node
docker-compose -f docker-compose.peer.yml up -d

# Watch logs for connection to seed
docker-compose -f docker-compose.peer.yml logs -f

# Look for messages like:
# "Connecting to seed peer: 192.168.1.10:6001"
# "IoT REST API initialized successfully"
```

### Step 7: Verify Peer Node

```bash
# Check health
curl http://localhost:8000/health

# Check network status
curl http://localhost:8000/api/v1/network/status | jq '.'

# Verify it shows IS_SEED=false and SEED_PEER
```

---

## Network Configuration

### Verify P2P Connection

**On PC1 (Seed Node):**

```bash
# Check connected peers
curl http://localhost:8000/api/v1/network/peers | jq '.'

# Check network status
curl http://localhost:8000/api/v1/network/status | jq '.'

# View logs for peer connections
docker-compose -f docker-compose.seed.yml logs | grep -i peer
```

**On PC2 (Peer Node):**

```bash
# Check connected peers
curl http://localhost:8000/api/v1/network/peers | jq '.'

# Check network status
curl http://localhost:8000/api/v1/network/status | jq '.'

# View logs for seed connection
docker-compose -f docker-compose.peer.yml logs | grep -i seed
```

### Verify Blockchain Sync

Both nodes should have the same blockchain height:

```bash
# On PC1
curl http://localhost:8000/api/v1/network/status | jq '.blockHeight'

# On PC2
curl http://localhost:8000/api/v1/network/status | jq '.blockHeight'

# Heights should match
```

---

## Testing IoT Transactions

### Option 1: Run from PC1

```bash
cd ~/blockchain/blockchain-java

# Set environment variables
export SEED_NODE_URL="http://localhost:8000"
export PEER_NODE_URL="http://192.168.1.20:8000"  # PC2 IP

# Run IoT test
./test_iot_transaction.sh
```

### Option 2: Run from PC2

```bash
cd ~/blockchain/blockchain-java

# Set environment variables
export SEED_NODE_URL="http://192.168.1.10:8000"  # PC1 IP
export PEER_NODE_URL="http://localhost:8000"

# Run IoT test
./test_iot_transaction.sh
```

### Option 3: Run from External PC

```bash
# Set both nodes as remote
export SEED_NODE_URL="http://192.168.1.10:8000"
export PEER_NODE_URL="http://192.168.1.20:8000"

# Run test
./test_iot_transaction.sh
```

### Expected Test Output

```
========================================
IoT BLOCKCHAIN TRANSACTION TEST
========================================

✓ All dependencies installed
✓ Seed Node (PC1) is ready
✓ Peer Node (PC2) is ready

========================================
STEP 2: Creating IoT Device Accounts
========================================

✓ Temperature Sensor created: 0x1a2b3c...
✓ Actuator device created: 0x4d5e6f...

========================================
STEP 5: Submitting IoT Sensor Data
========================================

  Data: temperature=23.5°C, humidity=65%
✓ Sensor data transaction: abc123...

========================================
STEP 6: Queuing Actuator Command
========================================

  Command: COOLING_ON for 300 seconds
✓ Actuator command transaction: def456...

...

✓ Nodes synchronized at height 5
✓ IoT blockchain transaction test finished!
```

### Manual IoT Transaction Testing

**Create Temperature Sensor Device (PC1):**

```bash
curl -X POST http://192.168.1.10:8000/api/v1/account/create \
  -H "Content-Type: application/json" | jq '.'

# Save the address and token
```

**Submit Sensor Reading (PC1):**

```bash
# Encode sensor data
SENSOR_DATA=$(echo -n '{"temp":25.3,"humidity":60}' | xxd -p | tr -d '\n')

curl -X POST http://192.168.1.10:8000/api/v1/transactions/submit \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TOKEN>" \
  -d "{
    \"type\": \"CONTRACT\",
    \"from\": \"<SENSOR_ADDRESS>\",
    \"to\": \"<ACTUATOR_ADDRESS>\",
    \"amount\": 0,
    \"fee\": 10,
    \"data\": \"$SENSOR_DATA\"
  }" | jq '.'
```

**Verify on PC2:**

```bash
# Check transaction propagated
curl http://192.168.1.20:8000/api/v1/transactions/<TX_ID> | jq '.'

# Check blockchain height matches
curl http://192.168.1.20:8000/api/v1/network/status | jq '.blockHeight'
```

---

## Troubleshooting

### Nodes Can't Connect

**Problem**: Peer node can't connect to seed node

**Solutions**:

1. **Check firewall on PC1**:
   ```bash
   sudo ufw status
   sudo ufw allow 6001/tcp
   ```

2. **Verify P2P port is listening on PC1**:
   ```bash
   sudo netstat -tlnp | grep 6001
   ```

3. **Test network connectivity**:
   ```bash
   # From PC2
   ping 192.168.1.10
   nc -zv 192.168.1.10 6001
   ```

4. **Check SEED_PEER environment variable on PC2**:
   ```bash
   docker exec blockchain-peer env | grep SEED_PEER
   # Should show: SEED_PEER=192.168.1.10:6001
   ```

5. **Check logs for connection errors**:
   ```bash
   # On PC2
   docker-compose -f docker-compose.peer.yml logs | grep -i error
   ```

### Blockchain Not Syncing

**Problem**: Block heights don't match

**Solutions**:

1. **Restart both nodes**:
   ```bash
   # PC1
   docker-compose -f docker-compose.seed.yml restart
   
   # PC2
   docker-compose -f docker-compose.peer.yml restart
   ```

2. **Check for errors in logs**:
   ```bash
   docker-compose logs | grep -i error
   ```

3. **Verify network connectivity**:
   ```bash
   # From PC2, test API access to PC1
   curl http://192.168.1.10:8000/health
   ```

### Transactions Not Propagating

**Problem**: Transaction appears on one node but not the other

**Solutions**:

1. **Check mempool on both nodes**:
   ```bash
   # PC1
   curl http://localhost:8000/api/v1/transactions/pending | jq '. | length'
   
   # PC2
   curl http://192.168.1.20:8000/api/v1/transactions/pending | jq '. | length'
   ```

2. **Wait for block mining**:
   ```bash
   # Transactions are confirmed when mined into a block
   # Wait 30-60 seconds and check again
   ```

3. **Check blockchain heights**:
   ```bash
   # If heights differ, nodes are out of sync
   curl http://192.168.1.10:8000/api/v1/network/status | jq '.blockHeight'
   curl http://192.168.1.20:8000/api/v1/network/status | jq '.blockHeight'
   ```

### Docker Image Transfer Issues

**Problem**: Image file too large or transfer fails

**Solutions**:

1. **Compress more aggressively**:
   ```bash
   docker save blockchain-java-seed-node:latest | gzip -9 > blockchain-node.tar.gz
   ```

2. **Use rsync for reliability**:
   ```bash
   rsync -avz --progress blockchain-node.tar.gz user@192.168.1.20:~/
   ```

3. **Split large file**:
   ```bash
   split -b 100M blockchain-node.tar.gz blockchain-node.tar.gz.part
   # Transfer parts separately, then rejoin on PC2:
   cat blockchain-node.tar.gz.part* > blockchain-node.tar.gz
   ```

### API Returns 401 Unauthorized

**Problem**: API calls fail with authorization error

**Solutions**:

1. **Use correct token**:
   ```bash
   # Get token when creating account
   TOKEN=$(curl -s -X POST http://localhost:8000/api/v1/account/create | jq -r '.token')
   
   # Use in subsequent requests
   curl -H "Authorization: Bearer $TOKEN" http://localhost:8000/api/v1/accounts/<ADDRESS>
   ```

2. **For testing, some endpoints may accept dummy token**:
   ```bash
   curl -H "Authorization: Bearer dummy" http://localhost:8000/api/v1/network/status
   ```

---

## Advanced: Adding More Peer Nodes

To add a third PC as another peer:

**PC3 Configuration:**

```yaml
version: '3.8'

services:
  peer-node-2:
    image: blockchain-java-seed-node:latest
    container_name: blockchain-peer-2
    environment:
      - NODE_ID=peer-node-pc3
      - NODE_PRIVATE_KEY=c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4
      - IS_SEED=false
      - SEED_PEER=192.168.1.10:6001  # Still points to PC1
    ports:
      - "8000:8000"
      - "6001:6001"
    volumes:
      - blockchain_data:/app/data

volumes:
  blockchain_data:
```

---

## Production Checklist

Before deploying to production:

- [ ] Change default private keys (use `openssl rand -hex 32`)
- [ ] Change STORAGE_AES_KEY to secure value
- [ ] Enable TLS/SSL for API endpoints
- [ ] Set up proper DNS names instead of IP addresses
- [ ] Configure backup strategy for blockchain data
- [ ] Set up monitoring and alerting
- [ ] Document disaster recovery procedures
- [ ] Test failover scenarios
- [ ] Implement log rotation
- [ ] Set up automated health checks

---

## Next Steps

1. ✅ **Test IoT Transactions**: Run `./test_iot_transaction.sh`
2. ✅ **Monitor Performance**: Use `docker stats` to watch resource usage
3. ✅ **Scale Network**: Add more peer nodes as needed
4. ✅ **Integrate IoT Devices**: Connect real sensors and actuators
5. ✅ **Deploy Smart Contracts**: Test contract execution across nodes

---

**Last Updated**: February 1, 2026
