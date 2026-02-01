# VirtualBox IoT Blockchain Setup Guide

This guide allows you to run a production-ready, multi-node IoT blockchain network using two VirtualBox VMs. This simulates a real physical deployment where each node is on a separate machine.

## 1. Prerequisites

### Host Machine (Your PC)
*   VirtualBox installed ([Download](https://www.virtualbox.org/wiki/Downloads))
*   `ssh` client (e.g., Terminal, PowerShell, or Git Bash)
*   The `blockchain-java` project built and Docker image created.

### Virtual Machines
Create **two** Ubuntu 22.04 LTS (Server or Desktop) VMs in VirtualBox with the following specs:
*   **RAM:** 2GB+ per VM
*   **CPU:** 2 CPUs
*   **Network Adapter:** **Bridged Adapter** (Important: This ensures each VM gets its own IP address on your LAN).

**Note:** Ensure you can ping both VMs from your Host PC and that they can ping each other.

---

## 2. Preparation on Host PC

### Step 1: Build the Docker Image (if not already built)
Navigate to the project root and build the image:
```bash
# If using the provided scripts (easiest)
./QUICK_START_TEST.sh
# OR manually
docker build -t blockchain-java:latest .
```

### Step 2: Export the Image
Export the Docker image to a file so we can transfer it to the VMs:
```bash
docker save blockchain-java:latest -o blockchain-java.tar
```
*This file will be about 200-300MB.*

### Step 3: Identify VM IP Addresses
Boot both VMs and check their IP addresses. inside each VM, run:
```bash
ip addr show
# Look for 'inet 192.168.x.x' or similar
```
*   **VM1 (Seed Node) IP:** e.g., `192.168.1.101`
*   **VM2 (Peer Node) IP:** e.g., `192.168.1.102`

---

## 3. Deployment on VM1 (Seed Node)

### Transfer Files
Copy the image and configuration to VM1:
```bash
# From Host PC
scp blockchain-java.tar user@<VM1_IP>:~/
scp vm-setup/seed-node/docker-compose.yml user@<VM1_IP>:~/docker-compose.yml
```

### Setup VM1
SSH into VM1 (`ssh user@<VM1_IP>`) and run:
```bash
# 1. Install Docker & Docker Compose
sudo apt update && sudo apt install -y docker.io docker-compose
sudo usermod -aG docker $USER
newgrp docker # Activate group change immediately

# 2. Load the Docker Image
docker load -i blockchain-java.tar

# 3. Start the Seed Node
docker-compose up -d

# 4. Verify it's running
docker logs -f seed-node
# You should see: "IoT Blockchain Node Starting ... Is Seed Node: true"
```

---

## 4. Deployment on VM2 (Peer Node)

### Transfer Files
Copy the image and configuration to VM2:
```bash
# From Host PC
scp blockchain-java.tar user@<VM2_IP>:~/
scp vm-setup/peer-node/docker-compose.yml user@<VM2_IP>:~/docker-compose.yml
```

### Setup VM2
SSH into VM2 (`ssh user@<VM2_IP>`) and run:
```bash
# 1. Install Docker & Docker Compose
sudo apt update && sudo apt install -y docker.io docker-compose
sudo usermod -aG docker $USER
newgrp docker

# 2. Load the Docker Image
docker load -i blockchain-java.tar

# 3. Configure Connection to Seed Node
# IMPORTANT: Edit docker-compose.yml to set the Seed Node IP
nano docker-compose.yml
```
Change the line:
```yaml
      - SEED_PEER=<SEED_NODE_IP>:6001
```
to (using VM1's IP):
```yaml
      - SEED_PEER=192.168.1.101:6001
```
Save and exit (`Ctrl+O`, `Enter`, `Ctrl+X`).

```bash
# 4. Start the Peer Node
docker-compose up -d

# 5. Verify Connection
docker logs -f peer-node
# You should see: "Connected to seed node..." or block synchronization logs.
```

---

## 5. Running IoT Transaction Tests

Now that both nodes are running on separate machines, you can run the automated IoT simulation script from your Host PC.

### Run the Test
Navigate to the project root on your Host PC:
```bash
cd vm-setup/tools

# Set the node URLs to point to your VMs
export SEED_NODE_URL="http://<VM1_IP>:8000"
export PEER_NODE_URL="http://<VM2_IP>:8000"

# Example:
# export SEED_NODE_URL="http://192.168.1.101:8000"
# export PEER_NODE_URL="http://192.168.1.102:8000"

# Run the test
./test_iot_transaction.sh
```

### What This Test Does
1.  **Creates Device Accounts:** Registers a Sensor on VM1 and an Actuator on VM2.
2.  **Funds Accounts:** Simulates coinbase funding.
3.  **Submits Sensor Data:** Sends `CONTRACT` transaction with temperature/humidity data.
4.  **Propagates Transaction:** Verifies that VM2 received the transaction from VM1.
5.  **Queues Actuator Command:** Sends command from VM2 back to VM1.
6.  **Verifies Sync:** Checks that both VMs have the same block height and data.

---

## Troubleshooting

*   **Connection Refused:** Check that VMs can ping each other. Ensure firewalls (`ufw`) allow ports 8000 and 6001.
    *   Disable firewall temporarily: `sudo ufw disable`
*   **Health Check Failed:** Ensure the containers are running: `docker ps`.
*   **Wait Loop Forever:** Use `docker logs <container_name>` to check for specific error messages (e.g., incorrect Key or Network ID).
