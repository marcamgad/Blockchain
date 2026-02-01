# IoT Blockchain - Docker Deployment

Production-ready containerized deployment for the IoT blockchain network with multi-node support, automated testing, and comprehensive documentation.

## 🚀 Quick Start

Get a 3-node blockchain network running in **under 5 minutes**:

```bash
# 1. Install dependencies
sudo apt-get update && sudo apt-get install -y docker.io docker-compose jq curl

# 2. Start the network
./scripts/start-network.sh

# 3. Run automated tests
./test_transaction.sh          # Basic transaction test
./test_iot_transaction.sh      # IoT-specific test
```

## 📋 What's Included

This deployment package provides everything needed for production IoT blockchain deployment:

### Docker Configuration
- ✅ **Dockerfile**: Multi-stage build with Maven, non-root user, health checks
- ✅ **docker-compose.yml**: 3-node network (1 seed + 2 peers)
- ✅ **Environment templates**: `.env.example` with all configuration options

### Testing Scripts
- ✅ **test_transaction.sh**: Basic transaction propagation test
- ✅ **test_iot_transaction.sh**: IoT device transactions with sensor data and actuator commands
- ✅ **Automated verification**: Balance checks, sync verification, transaction propagation

### Helper Scripts
- ✅ **scripts/start-network.sh**: Build and start all nodes
- ✅ **scripts/stop-network.sh**: Stop network (with optional data cleanup)
- ✅ **scripts/view-logs.sh**: View logs from specific or all nodes

### Documentation
- ✅ **DOCKER_DEPLOYMENT.md**: Complete single-machine deployment guide
- ✅ **MULTI_PC_DEPLOYMENT.md**: Step-by-step guide for deploying across multiple PCs
- ✅ **ISO_VM_SETUP.md**: Creating bootable VMs and ISOs for testing

## 🏗️ Architecture

### Single Machine (Development)

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

### Multi-PC (Production)

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

## 📦 Deployment Options

### Option 1: Single Machine (Development/Testing)

Perfect for development and local testing.

```bash
# Start 3-node network
docker-compose up -d

# Access nodes
curl http://localhost:8000/health  # Seed node
curl http://localhost:8001/health  # Peer 1
curl http://localhost:8002/health  # Peer 2

# Run tests
./test_iot_transaction.sh
```

**See**: [DOCKER_DEPLOYMENT.md](DOCKER_DEPLOYMENT.md)

### Option 2: Multi-PC (Production)

Deploy across multiple physical computers for true distributed network.

**PC1 (Seed Node)**:
```bash
# Build and export image
docker-compose -f docker-compose.seed.yml build
docker save blockchain-java-seed-node:latest | gzip > blockchain-node.tar.gz

# Transfer to PC2 (USB, SCP, or HTTP)
scp blockchain-node.tar.gz user@192.168.1.20:~/

# Start seed node
docker-compose -f docker-compose.seed.yml up -d
```

**PC2 (Peer Node)**:
```bash
# Load image
docker load < blockchain-node.tar.gz

# Configure and start (update SEED_PEER with PC1 IP)
docker-compose -f docker-compose.peer.yml up -d
```

**See**: [MULTI_PC_DEPLOYMENT.md](MULTI_PC_DEPLOYMENT.md)

### Option 3: VM/ISO (Automated Testing)

Create bootable VMs or ISOs with blockchain pre-installed.

- VirtualBox/VMware VMs with auto-start
- Custom bootable ISOs
- Vagrant boxes for team distribution

**See**: [ISO_VM_SETUP.md](ISO_VM_SETUP.md)

## 🧪 Testing

### Basic Transaction Test

Tests fundamental blockchain operations:

```bash
./test_transaction.sh
```

**Tests**:
- ✅ Node health and readiness
- ✅ Account creation
- ✅ Transaction submission
- ✅ Block mining
- ✅ Transaction propagation
- ✅ Balance updates
- ✅ Blockchain synchronization

### IoT Transaction Test

Tests IoT-specific features:

```bash
./test_iot_transaction.sh
```

**Tests**:
- ✅ IoT device account creation (sensors, actuators)
- ✅ Sensor data submission via smart contracts
- ✅ Actuator command queuing
- ✅ CONTRACT transaction type
- ✅ Cross-node IoT data propagation
- ✅ Device lifecycle management

### Multi-PC Testing

Test across physical machines:

```bash
# Set node URLs
export SEED_NODE_URL="http://192.168.1.10:8000"
export PEER_NODE_URL="http://192.168.1.20:8000"

# Run test
./test_iot_transaction.sh
```

## 🔧 Configuration

### Environment Variables

All nodes support these environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `NODE_ID` | Unique node identifier | `node-<timestamp>` |
| `NODE_NAME` | Human-readable node name | `HybridJavaNode` |
| `NODE_PRIVATE_KEY` | Node's private key (hex) | **Required** |
| `IS_SEED` | Whether this is a seed node | `false` |
| `SEED_PEER` | Seed node address (host:port) | `null` |
| `API_PORT` | REST API port | `8000` |
| `P2P_PORT` | P2P communication port | `6001` |
| `STORAGE_PATH` | Blockchain data directory | `./data` |
| `NETWORK_ID` | Network identifier | `101` |
| `PROTOCOL_VERSION` | Protocol version | `1.0.0` |
| `STORAGE_AES_KEY` | Encryption key for storage | `1234567890abcdef` |
| `JAVA_OPTS` | JVM options | `-Xmx2G -Xms512M` |

### Port Mapping

**Single Machine**:
- Seed Node: API `8000`, P2P `6001`
- Peer 1: API `8001`, P2P `6002`
- Peer 2: API `8002`, P2P `6003`

**Multi-PC**:
- Each PC: API `8000`, P2P `6001`
- Firewall must allow P2P port `6001`

## 📊 API Endpoints

### Health & Status

```bash
# Health check (for Docker healthcheck)
GET /health

# Readiness check
GET /ready

# Network status
GET /api/v1/network/status

# Connected peers
GET /api/v1/network/peers
```

### Accounts

```bash
# Create account
POST /api/v1/account/create

# Get account balance
GET /api/v1/accounts/{address}
```

### Transactions

```bash
# Submit transaction
POST /api/v1/transactions/submit

# Get transaction
GET /api/v1/transactions/{txId}

# Get pending transactions
GET /api/v1/transactions/pending
```

### Blocks

```bash
# Get latest block
GET /api/v1/blocks/latest

# Get block by height
GET /api/v1/blocks/height/{height}

# Get block by hash
GET /api/v1/blocks/{hash}
```

### Smart Contracts

```bash
# Deploy contract
POST /api/v1/contracts/deploy

# Call contract
POST /api/v1/contracts/{address}/call
```

## 🔐 Security

### Production Checklist

Before deploying to production:

- [ ] **Generate unique private keys** for each node:
  ```bash
  openssl rand -hex 32
  ```

- [ ] **Change STORAGE_AES_KEY** to secure random value:
  ```bash
  openssl rand -hex 16
  ```

- [ ] **Enable TLS/SSL** for API endpoints (use nginx reverse proxy)

- [ ] **Configure firewall rules**:
  ```bash
  sudo ufw allow 6001/tcp  # P2P only
  sudo ufw deny 8000/tcp   # Block external API access
  ```

- [ ] **Use Docker secrets** instead of environment variables for sensitive data

- [ ] **Enable authentication** for API endpoints

- [ ] **Set up monitoring** and alerting

- [ ] **Configure backup strategy** for blockchain data

## 📈 Monitoring

### View Logs

```bash
# All nodes
./scripts/view-logs.sh

# Specific node
./scripts/view-logs.sh seed
./scripts/view-logs.sh peer1
./scripts/view-logs.sh peer2
```

### Resource Usage

```bash
# Real-time stats
docker stats

# Specific container
docker stats blockchain-seed
```

### Health Checks

```bash
# Check all nodes
for port in 8000 8001 8002; do
  echo "Node on port $port:"
  curl -s http://localhost:$port/health | jq '.'
done
```

## 🛠️ Troubleshooting

### Common Issues

**Nodes won't start**:
```bash
# Check logs
docker-compose logs seed-node

# Common causes:
# - Port already in use
# - Missing NODE_PRIVATE_KEY
# - Insufficient memory
```

**Nodes not connecting**:
```bash
# Verify network connectivity
docker exec blockchain-peer-1 ping seed-node

# Check P2P port
sudo netstat -tlnp | grep 6001

# Verify SEED_PEER setting
docker exec blockchain-peer-1 env | grep SEED_PEER
```

**Transactions not propagating**:
```bash
# Check mempool
curl http://localhost:8000/api/v1/transactions/pending | jq '. | length'
curl http://localhost:8001/api/v1/transactions/pending | jq '. | length'

# Check blockchain heights
curl http://localhost:8000/api/v1/network/status | jq '.blockHeight'
curl http://localhost:8001/api/v1/network/status | jq '.blockHeight'
```

**See full troubleshooting guide**: [DOCKER_DEPLOYMENT.md#troubleshooting](DOCKER_DEPLOYMENT.md#troubleshooting)

## 📚 Documentation

- **[DOCKER_DEPLOYMENT.md](DOCKER_DEPLOYMENT.md)**: Complete single-machine deployment guide
- **[MULTI_PC_DEPLOYMENT.md](MULTI_PC_DEPLOYMENT.md)**: Multi-PC deployment with detailed network setup
- **[ISO_VM_SETUP.md](ISO_VM_SETUP.md)**: Creating VMs and bootable ISOs
- **[BUILD_AND_RUN.md](BUILD_AND_RUN.md)**: Development build instructions

## 🎯 Use Cases

### Development
```bash
# Quick local testing
docker-compose up -d
./test_transaction.sh
```

### Integration Testing
```bash
# Multi-node IoT testing
./test_iot_transaction.sh
```

### Production Deployment
```bash
# Multi-PC with monitoring
# See MULTI_PC_DEPLOYMENT.md
```

### Demo/Training
```bash
# VM with auto-start
# See ISO_VM_SETUP.md
```

## 🤝 Contributing

When adding new features:

1. Update Dockerfile if dependencies change
2. Update docker-compose.yml if new services needed
3. Add tests to test_iot_transaction.sh
4. Update documentation

## 📝 License

See [LICENSE](../LICENSE) file for details.

## 🆘 Support

For issues or questions:

1. Check [DOCKER_DEPLOYMENT.md](DOCKER_DEPLOYMENT.md) troubleshooting section
2. Review logs: `./scripts/view-logs.sh`
3. Verify configuration: `docker-compose config`
4. Check GitHub issues

---

**Last Updated**: February 1, 2026  
**Version**: 1.0.0  
**Status**: Production Ready ✅
