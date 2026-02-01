# Docker Deployment Guide

Complete guide for deploying the IoT Blockchain network using Docker containers.

## Table of Contents

- [Quick Start](#quick-start)
- [Prerequisites](#prerequisites)
- [Single Machine Setup](#single-machine-setup)
- [Multi-PC Setup](#multi-pc-setup)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)
- [Advanced Configuration](#advanced-configuration)

---

## Quick Start

Get a 3-node blockchain network running in under 5 minutes:

```bash
# 1. Install dependencies
sudo apt-get update
sudo apt-get install -y docker.io docker-compose jq curl

# 2. Start the network
cd ~/Blockchain/blockchain-java
./scripts/start-network.sh

# 3. Wait for nodes to be ready (about 60 seconds)
# Watch the logs
./scripts/view-logs.sh

# 4. Run automated tests
./test_transaction.sh
```

---

## Prerequisites

### Required Software

1. **Docker** (20.10+)
   ```bash
   # Install Docker
   curl -fsSL https://get.docker.com -o get-docker.sh
   sudo sh get-docker.sh
   
   # Add user to docker group (logout/login required)
   sudo usermod -aG docker $USER
   ```

2. **Docker Compose** (2.0+)
   ```bash
   # Usually comes with Docker, verify:
   docker-compose --version
   
   # If not installed:
   sudo apt-get install docker-compose-plugin
   ```

3. **jq** (for JSON parsing in test scripts)
   ```bash
   sudo apt-get install jq
   ```

4. **curl** (for API testing)
   ```bash
   sudo apt-get install curl
   ```

### System Requirements

- **CPU**: 2+ cores recommended
- **RAM**: 4GB minimum, 8GB recommended
- **Disk**: 10GB free space
- **Network**: Ports 8000-8002 (API) and 6001-6003 (P2P) available

---

## Single Machine Setup

### Architecture

```
┌─────────────────────────────────────────────────┐
│              Host Machine (localhost)            │
│                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────┐│
│  │  Seed Node   │  │ Peer Node 1  │  │ Peer 2  ││
│  │  API: 8000   │  │  API: 8001   │  │ API:8002││
│  │  P2P: 6001   │  │  P2P: 6002   │  │ P2P:6003││
│  └──────┬───────┘  └──────┬───────┘  └────┬────┘│
│         │                 │                │     │
│         └─────────────────┴────────────────┘     │
│              blockchain-net (172.25.0.0/16)      │
└─────────────────────────────────────────────────┘
```

### Step-by-Step Instructions

#### 1. Build Docker Images

```bash
cd ~/Blockchain/blockchain-java

# Build images (takes 3-5 minutes first time)
docker-compose build

# Verify images
docker images | grep blockchain
```

Expected output:
```
blockchain-java-seed-node    latest    abc123...    2 minutes ago    350MB
blockchain-java-peer-node-1  latest    def456...    2 minutes ago    350MB
blockchain-java-peer-node-2  latest    ghi789...    2 minutes ago    350MB
```

#### 2. Start the Network

```bash
# Start all nodes
./scripts/start-network.sh

# Or manually:
docker-compose up -d
```

#### 3. Verify Nodes are Running

```bash
# Check container status
docker-compose ps

# Should show 3 containers with "healthy" status
```

#### 4. Check Health Endpoints

```bash
# Seed node
curl http://localhost:8000/health

# Peer node 1
curl http://localhost:8001/health

# Peer node 2
curl http://localhost:8002/health
```

All should return `{"status":"healthy"}` or similar.

#### 5. View Logs

```bash
# All nodes
./scripts/view-logs.sh

# Specific node
./scripts/view-logs.sh seed
./scripts/view-logs.sh peer1
./scripts/view-logs.sh peer2
```

#### 6. Run Automated Tests

```bash
./test_transaction.sh
```

This will:
- Create two accounts
- Fund one account
- Send a transaction
- Verify propagation across all nodes
- Check balances

### Stopping the Network

```bash
# Stop and preserve data
./scripts/stop-network.sh

# Stop and clear all data
./scripts/stop-network.sh --clear-data

# Or manually:
docker-compose down
docker-compose down -v  # Also remove volumes
```

---

## Multi-PC Setup

Deploy nodes across multiple physical machines for a true distributed network.

### Architecture

```
┌─────────────────────┐         ┌─────────────────────┐
│   PC 1 (Seed Node)  │         │  PC 2 (Peer Node)   │
│   IP: 192.168.1.10  │◄───────►│  IP: 192.168.1.20   │
│                     │         │                     │
│  ┌──────────────┐   │         │  ┌──────────────┐   │
│  │  Seed Node   │   │         │  │ Peer Node 1  │   │
│  │  API: 8000   │   │         │  │  API: 8000   │   │
│  │  P2P: 6001   │   │         │  │  P2P: 6001   │   │
│  └──────────────┘   │         │  └──────────────┘   │
└─────────────────────┘         └─────────────────────┘
```

### Prerequisites

1. **Network connectivity** between PCs
2. **Firewall rules** allowing ports 6001 (P2P) and 8000 (API)
3. **Static IPs** or DNS names for each PC

### PC 1: Seed Node Setup

#### 1. Create docker-compose.seed.yml

```yaml
version: '3.8'

services:
  seed-node:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: blockchain-seed
    hostname: seed-node
    environment:
      - NODE_ID=seed-node
      - NODE_PRIVATE_KEY=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2
      - IS_SEED=true
      - API_PORT=8000
      - P2P_PORT=6001
      - STORAGE_PATH=/app/data
      - NETWORK_ID=101
      - PROTOCOL_VERSION=1.0.0
      - STORAGE_AES_KEY=1234567890abcdef
    ports:
      - "8000:8000"
      - "6001:6001"
    volumes:
      - seed_data:/app/data
    restart: unless-stopped

volumes:
  seed_data:
```

#### 2. Start Seed Node

```bash
docker-compose -f docker-compose.seed.yml up -d

# Verify
curl http://localhost:8000/health
```

#### 3. Configure Firewall

```bash
# Allow P2P port
sudo ufw allow 6001/tcp

# Allow API port (optional, for remote access)
sudo ufw allow 8000/tcp

# Reload firewall
sudo ufw reload
```

### PC 2: Peer Node Setup

#### 1. Create docker-compose.peer.yml

Replace `SEED_NODE_IP` with PC 1's IP address (e.g., 192.168.1.10):

```yaml
version: '3.8'

services:
  peer-node:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: blockchain-peer
    hostname: peer-node
    environment:
      - NODE_ID=peer-node-1
      - NODE_PRIVATE_KEY=b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3
      - IS_SEED=false
      - SEED_PEER=192.168.1.10:6001  # ← Change this to seed node IP
      - API_PORT=8000
      - P2P_PORT=6001
      - STORAGE_PATH=/app/data
      - NETWORK_ID=101
      - PROTOCOL_VERSION=1.0.0
      - STORAGE_AES_KEY=1234567890abcdef
    ports:
      - "8000:8000"
      - "6001:6001"
    volumes:
      - peer_data:/app/data
    restart: unless-stopped

volumes:
  peer_data:
```

#### 2. Start Peer Node

```bash
docker-compose -f docker-compose.peer.yml up -d

# Verify
curl http://localhost:8000/health
```

#### 3. Configure Firewall

```bash
sudo ufw allow 6001/tcp
sudo ufw allow 8000/tcp
sudo ufw reload
```

### Verify Multi-PC Network

From **PC 1** (seed node):

```bash
# Check network status
curl http://localhost:8000/api/network/status

# Should show connected peers
curl http://localhost:8000/api/peers
```

From **PC 2** (peer node):

```bash
# Check network status
curl http://localhost:8000/api/network/status

# Should show connection to seed
curl http://localhost:8000/api/peers
```

### Testing Across PCs

From **PC 1**:

```bash
# Create account
ACCOUNT1=$(curl -s -X POST http://localhost:8000/api/account/create)
echo $ACCOUNT1 | jq '.'
```

From **PC 2**:

```bash
# Verify account propagated
ADDRESS="<address_from_pc1>"
curl http://localhost:8000/api/account/$ADDRESS
```

---

## Testing

### Automated Testing

Run the comprehensive test suite:

```bash
./test_transaction.sh
```

This tests:
- ✅ Node health and readiness
- ✅ Account creation
- ✅ Transaction submission
- ✅ Block mining
- ✅ Transaction propagation
- ✅ Balance updates
- ✅ Blockchain synchronization

### Manual API Testing

#### Create Account

```bash
curl -X POST http://localhost:8000/api/account/create \
  -H "Content-Type: application/json"
```

#### Get Account Balance

```bash
curl http://localhost:8000/api/account/<ADDRESS> \
  -H "Authorization: Bearer dummy"
```

#### Submit Transaction

```bash
curl -X POST http://localhost:8000/api/transaction/submit \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer dummy" \
  -d '{
    "type": "TRANSFER",
    "from": "<FROM_ADDRESS>",
    "to": "<TO_ADDRESS>",
    "amount": 100,
    "fee": 1
  }'
```

#### Get Transaction Status

```bash
curl http://localhost:8000/api/transaction/<TX_ID>
```

#### Get Latest Block

```bash
curl http://localhost:8000/api/block/latest
```

#### Get Network Status

```bash
curl http://localhost:8000/api/network/status
```

---

## Troubleshooting

### Nodes Won't Start

**Problem**: Containers exit immediately

**Solution**:
```bash
# Check logs
docker-compose logs seed-node

# Common issues:
# 1. Port already in use
sudo lsof -i :8000
sudo lsof -i :6001

# 2. Missing NODE_PRIVATE_KEY
# Ensure it's set in docker-compose.yml
```

### Health Check Fails

**Problem**: `/health` endpoint returns error

**Solution**:
```bash
# Check if Spring Boot started
docker-compose logs seed-node | grep "Started IoTRestAPI"

# Check Java errors
docker-compose logs seed-node | grep -i error

# Increase startup time
# Edit docker-compose.yml, increase start_period to 120s
```

### Nodes Not Connecting

**Problem**: Peers can't discover seed node

**Solution**:
```bash
# Verify network connectivity
docker exec blockchain-peer-1 ping seed-node

# Check P2P port is listening
docker exec blockchain-seed netstat -tlnp | grep 6001

# Verify SEED_PEER environment variable
docker exec blockchain-peer-1 env | grep SEED_PEER
```

### Transaction Not Propagating

**Problem**: Transaction appears on one node but not others

**Solution**:
```bash
# Check mempool on all nodes
curl http://localhost:8000/api/mempool/pending
curl http://localhost:8001/api/mempool/pending
curl http://localhost:8002/api/mempool/pending

# Check blockchain heights
curl http://localhost:8000/api/network/status | jq '.blockHeight'
curl http://localhost:8001/api/network/status | jq '.blockHeight'

# Restart nodes if out of sync
docker-compose restart
```

### Database Locked

**Problem**: LevelDB lock error

**Solution**:
```bash
# Stop all containers
docker-compose down

# Clear lock files
docker volume rm blockchain-java_seed_data
docker volume rm blockchain-java_peer1_data
docker volume rm blockchain-java_peer2_data

# Restart
docker-compose up -d
```

---

## Advanced Configuration

### Custom Node Configuration

Create a `.env` file:

```bash
# Copy example
cp .env.example .env

# Edit values
nano .env
```

Use in docker-compose.yml:

```yaml
services:
  seed-node:
    env_file: .env
```

### Adding More Peer Nodes

Edit `docker-compose.yml`:

```yaml
  peer-node-3:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: blockchain-peer-3
    environment:
      - NODE_ID=peer-node-3
      - NODE_PRIVATE_KEY=<UNIQUE_KEY>
      - SEED_PEER=seed-node:6001
      - API_PORT=8000
      - P2P_PORT=6001
    ports:
      - "8003:8000"
      - "6004:6001"
    networks:
      - blockchain-net
    volumes:
      - peer3_data:/app/data
    depends_on:
      seed-node:
        condition: service_healthy

volumes:
  peer3_data:
```

### Performance Tuning

#### Increase JVM Memory

Edit `docker-compose.yml`:

```yaml
environment:
  - JAVA_OPTS=-Xmx4G -Xms1G
```

#### Optimize LevelDB

Set environment variables:

```yaml
environment:
  - LEVELDB_CACHE_SIZE=256MB
  - LEVELDB_WRITE_BUFFER_SIZE=64MB
```

### Security Hardening

#### 1. Generate Secure Private Keys

```bash
# Generate unique key for each node
openssl rand -hex 32
```

#### 2. Use Secrets for Sensitive Data

Create `secrets/` directory:

```bash
mkdir secrets
echo "your-secure-key" > secrets/node_private_key
chmod 600 secrets/node_private_key
```

Update docker-compose.yml:

```yaml
services:
  seed-node:
    secrets:
      - node_private_key
    environment:
      - NODE_PRIVATE_KEY_FILE=/run/secrets/node_private_key

secrets:
  node_private_key:
    file: ./secrets/node_private_key
```

#### 3. Enable TLS for API

Add nginx reverse proxy with SSL:

```yaml
  nginx:
    image: nginx:alpine
    ports:
      - "443:443"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf
      - ./certs:/etc/nginx/certs
    depends_on:
      - seed-node
```

### Backup and Restore

#### Backup Blockchain Data

```bash
# Backup volumes
docker run --rm \
  -v blockchain-java_seed_data:/data \
  -v $(pwd)/backups:/backup \
  alpine tar czf /backup/seed-data-$(date +%Y%m%d).tar.gz /data
```

#### Restore Blockchain Data

```bash
# Restore from backup
docker run --rm \
  -v blockchain-java_seed_data:/data \
  -v $(pwd)/backups:/backup \
  alpine tar xzf /backup/seed-data-20260201.tar.gz -C /
```

### Monitoring

#### View Resource Usage

```bash
# Real-time stats
docker stats

# Specific container
docker stats blockchain-seed
```

#### Export Metrics

Add Prometheus exporter:

```yaml
  prometheus:
    image: prom/prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
```

---

## Next Steps

1. ✅ **Production Deployment**: See [ISO_VM_SETUP.md](ISO_VM_SETUP.md) for automated environments
2. ✅ **API Documentation**: Explore all endpoints at `http://localhost:8000/api/docs`
3. ✅ **Smart Contracts**: Deploy and test smart contracts
4. ✅ **Load Testing**: Use tools like Apache JMeter to test throughput
5. ✅ **Monitoring**: Set up Grafana dashboards for metrics

---

## Support

For issues or questions:

1. Check logs: `./scripts/view-logs.sh`
2. Review this guide's troubleshooting section
3. Check GitHub issues
4. Consult [BUILD_AND_RUN.md](BUILD_AND_RUN.md) for development setup

---

**Last Updated**: February 1, 2026
