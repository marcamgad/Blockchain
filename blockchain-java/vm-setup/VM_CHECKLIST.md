# VM Parallel Setup Checklist

Follow these steps for **BOTH** VMs simultaneously to save time.

## 1. Preparation (While ISO Downloads)
- [ ] **Download Ubuntu Server 22.04 ISO**
- [ ] **Prepare Docker Image on Host:**
  ```bash
  cd ~/Blockchain/blockchain-java
  docker build -t blockchain-java:latest .
  docker save blockchain-java:latest -o vm-setup/blockchain-java.tar
  ```
- [ ] **Prepare VM Files Folder (`~/vm-setup`):**
  - Contains: `blockchain-java.tar`, `docker-compose.yml` (individual ones preferably), scripts.
- [ ] **Network Planning:**
  - VM1 (Seed): will need static IP or known IP.
  - VM2 (Peer): will need to know VM1's IP.

## 2. Create VMs (VirtualBox)
- [ ] **Create VM 1:** Name "Blockchain-Seed", 2GB RAM, 20GB Disk, Bridged Network.
- [ ] **Create VM 2:** Name "Blockchain-Peer", 2GB RAM, 20GB Disk, Bridged Network.
- [ ] **Mount ISO:** Settings -> Storage -> Empty -> Choose `ubuntu-live-server.iso`.

## 3. Install Ubuntu (Parallel)
- [ ] **Boot both VMs.**
- [ ] **Language/Keyboard:** English / default.
- [ ] **Network:** Ensure they get an IP address (DHCP is fine, note it down).
  - VM1 IP: `________________`
  - VM2 IP: `________________`
- [ ] **Storage:** Use entire disk (default).
- [ ] **Profile Setup:** User `ubuntu`, Password `password` (or your choice).
- [ ] **SSH Setup:** [x] Install OpenSSH server (check the box).
- [ ] **Finish Installation:** Reboot and remove ISO.

## 4. Install Dependencies (SSH into each)
*Tip: SSH from host for easier copy-pasting: `ssh ubuntu@<VM_IP>`*

Run on **BOTH** VMs:
```bash
# Update and install Docker
sudo apt-get update
sudo apt-get install -y docker.io docker-compose
sudo usermod -aG docker $USER
newgrp docker
```

## 5. Transfer Files
On Host Machine, copy files to VMs:
```bash
# To VM 1 (Seed)
scp ~/vm-setup/blockchain-java.tar ubuntu@<VM1_IP>:~/
scp ~/vm-setup/seed-node/docker-compose.yml ubuntu@<VM1_IP>:~/docker-compose.yml
scp ~/vm-setup/QUICK_START_TEST.sh ubuntu@<VM1_IP>:~/
scp ~/vm-setup/VM_TEST.sh ubuntu@<VM1_IP>:~/

# To VM 2 (Peer)
scp ~/vm-setup/blockchain-java.tar ubuntu@<VM2_IP>:~/
scp ~/vm-setup/peer-node/docker-compose.yml ubuntu@<VM2_IP>:~/docker-compose.yml
scp ~/vm-setup/QUICK_START_TEST.sh ubuntu@<VM2_IP>:~/
scp ~/vm-setup/VM_TEST.sh ubuntu@<VM2_IP>:~/
```

## 6. Load Docker Image
Run on **BOTH** VMs:
```bash
docker load -i blockchain-java.tar
```

## 7. Configure Peer Node (VM 2 Only)
- [ ] Edit `docker-compose.yml` on VM 2:
  ```bash
  nano docker-compose.yml
  ```
- [ ] Change `SEED_PEER` value to VM 1's IP:
  ```yaml
  - SEED_PEER=<VM1_IP>:6001
  ```
- [ ] Save (Ctrl+O, Enter, Ctrl+X).

## 8. Start Network
- [ ] **VM 1 (Seed):**
  ```bash
  docker-compose up -d
  ```
- [ ] **VM 2 (Peer):** (Wait for Seed to start first)
  ```bash
  docker-compose up -d
  ```

## 9. Verification
- [ ] Check logs: `docker-compose logs -f`
- [ ] Run Test:
  ```bash
  chmod +x VM_TEST.sh
  ./VM_TEST.sh
  ```
- [ ] Test Connectivity (from Host):
  ```bash
  # Check Seed
  curl http://<VM1_IP>:8000/api/v1/network/status
  
  # Check Peer
  curl http://<VM2_IP>:8000/api/v1/network/status
  ```
