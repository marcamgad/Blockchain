# ISO/VM Setup Guide

Guide for creating bootable test environments with Docker and blockchain nodes pre-configured.

## Table of Contents

- [Overview](#overview)
- [Option 1: Docker-based VM](#option-1-docker-based-vm)
- [Option 2: Custom Bootable ISO](#option-2-custom-bootable-iso)
- [Option 3: Vagrant Box](#option-3-vagrant-box)
- [Testing the Environment](#testing-the-environment)

---

## Overview

This guide provides three approaches for creating automated test environments:

1. **Docker-based VM**: Virtual machine with Docker and blockchain pre-installed
2. **Custom ISO**: Bootable ISO with everything embedded
3. **Vagrant Box**: Reproducible development environment

Choose based on your needs:
- **VM**: Best for demos and testing
- **ISO**: Best for physical deployment
- **Vagrant**: Best for development teams

---

## Option 1: Docker-based VM

Create a VirtualBox/VMware VM that boots with the blockchain network running.

### Step 1: Create Base VM

#### Using VirtualBox

```bash
# Download Ubuntu Server 22.04 LTS
wget https://releases.ubuntu.com/22.04/ubuntu-22.04.3-live-server-amd64.iso

# Create VM
VBoxManage createvm --name "Blockchain-Node" --ostype Ubuntu_64 --register

# Configure VM
VBoxManage modifyvm "Blockchain-Node" \
  --memory 4096 \
  --cpus 2 \
  --nic1 nat \
  --natpf1 "api,tcp,,8000,,8000" \
  --natpf1 "api1,tcp,,8001,,8001" \
  --natpf1 "api2,tcp,,8002,,8002" \
  --natpf1 "p2p,tcp,,6001,,6001" \
  --natpf1 "p2p1,tcp,,6002,,6002" \
  --natpf1 "p2p2,tcp,,6003,,6003"

# Create disk
VBoxManage createhd --filename ~/VirtualBox\ VMs/Blockchain-Node/disk.vdi --size 20480

# Attach disk
VBoxManage storagectl "Blockchain-Node" --name "SATA" --add sata --controller IntelAhci
VBoxManage storageattach "Blockchain-Node" --storagectl "SATA" --port 0 --device 0 --type hdd --medium ~/VirtualBox\ VMs/Blockchain-Node/disk.vdi

# Attach ISO
VBoxManage storageattach "Blockchain-Node" --storagectl "SATA" --port 1 --device 0 --type dvddrive --medium ubuntu-22.04.3-live-server-amd64.iso

# Start VM and install Ubuntu
VBoxManage startvm "Blockchain-Node"
```

### Step 2: Install Docker and Dependencies

SSH into the VM and run:

```bash
# Update system
sudo apt-get update
sudo apt-get upgrade -y

# Install Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# Install Docker Compose
sudo apt-get install -y docker-compose-plugin

# Install utilities
sudo apt-get install -y git curl jq

# Add user to docker group
sudo usermod -aG docker $USER
```

### Step 3: Clone and Build Blockchain

```bash
# Clone repository
git clone <your-repo-url> ~/blockchain
cd ~/blockchain/blockchain-java

# Build Docker images
docker-compose build

# Test startup
docker-compose up -d
docker-compose ps
```

### Step 4: Create Auto-Start Service

Create systemd service to start blockchain on boot:

```bash
sudo nano /etc/systemd/system/blockchain.service
```

Add:

```ini
[Unit]
Description=Blockchain Network
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/home/ubuntu/blockchain/blockchain-java
ExecStart=/usr/bin/docker-compose up -d
ExecStop=/usr/bin/docker-compose down
User=ubuntu

[Install]
WantedBy=multi-user.target
```

Enable the service:

```bash
sudo systemctl daemon-reload
sudo systemctl enable blockchain.service
sudo systemctl start blockchain.service

# Verify
sudo systemctl status blockchain.service
```

### Step 5: Create VM Snapshot

```bash
# Shutdown VM
sudo shutdown -h now

# Create snapshot (from host)
VBoxManage snapshot "Blockchain-Node" take "Clean-Blockchain-Ready" --description "VM with blockchain ready to run"
```

### Step 6: Export VM

```bash
# Export as OVA
VBoxManage export "Blockchain-Node" -o blockchain-node.ova

# Distribute the OVA file
# Users can import with: VBoxManage import blockchain-node.ova
```

### Using the VM

1. **Import OVA**:
   ```bash
   VBoxManage import blockchain-node.ova
   ```

2. **Start VM**:
   ```bash
   VBoxManage startvm "Blockchain-Node" --type headless
   ```

3. **Access from host**:
   ```bash
   # API endpoints
   curl http://localhost:8000/health
   curl http://localhost:8001/health
   curl http://localhost:8002/health
   ```

4. **SSH into VM**:
   ```bash
   ssh ubuntu@localhost -p 2222  # Configure port forwarding
   ```

---

## Option 2: Custom Bootable ISO

Create a custom Ubuntu ISO with blockchain embedded.

### Prerequisites

```bash
sudo apt-get install -y \
  debootstrap \
  squashfs-tools \
  xorriso \
  grub-pc-bin \
  grub-efi-amd64-bin \
  mtools
```

### Step 1: Create Base System

```bash
# Create working directory
mkdir -p ~/blockchain-iso
cd ~/blockchain-iso

# Download Ubuntu base
wget http://archive.ubuntu.com/ubuntu/dists/jammy/main/installer-amd64/current/legacy-images/netboot/mini.iso

# Extract ISO
mkdir iso-extract
sudo mount -o loop mini.iso iso-extract
mkdir iso-new
sudo cp -rT iso-extract iso-new
sudo umount iso-extract
```

### Step 2: Customize ISO

Create preseed file for automated installation:

```bash
cat > iso-new/preseed.cfg << 'EOF'
# Locale
d-i debian-installer/locale string en_US.UTF-8

# Keyboard
d-i keyboard-configuration/xkb-keymap select us

# Network
d-i netcfg/choose_interface select auto
d-i netcfg/get_hostname string blockchain-node

# Users
d-i passwd/user-fullname string Blockchain User
d-i passwd/username string blockchain
d-i passwd/user-password password blockchain
d-i passwd/user-password-again password blockchain

# Partitioning
d-i partman-auto/method string regular
d-i partman-auto/choose_recipe select atomic
d-i partman/confirm_write_new_label boolean true
d-i partman/choose_partition select finish
d-i partman/confirm boolean true

# Packages
tasksel tasksel/first multiselect standard
d-i pkgsel/include string openssh-server docker.io docker-compose git curl jq

# Grub
d-i grub-installer/only_debian boolean true

# Post-install script
d-i preseed/late_command string \
  in-target git clone <your-repo> /home/blockchain/blockchain; \
  in-target chown -R blockchain:blockchain /home/blockchain; \
  in-target systemctl enable docker
EOF
```

### Step 3: Add Blockchain Files

```bash
# Create custom packages directory
mkdir -p iso-new/pool/extras

# Copy blockchain project
tar czf iso-new/pool/extras/blockchain.tar.gz -C ~/Blockchain/blockchain-java .

# Create install script
cat > iso-new/pool/extras/install-blockchain.sh << 'EOF'
#!/bin/bash
cd /home/blockchain
tar xzf /cdrom/pool/extras/blockchain.tar.gz
docker-compose build
systemctl enable blockchain.service
EOF

chmod +x iso-new/pool/extras/install-blockchain.sh
```

### Step 4: Build ISO

```bash
# Update ISO metadata
cd iso-new
sudo nano isolinux/txt.cfg  # Add preseed option

# Rebuild ISO
cd ~/blockchain-iso
sudo xorriso -as mkisofs \
  -r -V "Blockchain Node" \
  -o blockchain-node.iso \
  -J -joliet-long \
  -b isolinux/isolinux.bin \
  -c isolinux/boot.cat \
  -no-emul-boot \
  -boot-load-size 4 \
  -boot-info-table \
  -eltorito-alt-boot \
  -e boot/grub/efi.img \
  -no-emul-boot \
  iso-new
```

### Step 5: Test ISO

```bash
# Create test VM
VBoxManage createvm --name "Test-ISO" --ostype Ubuntu_64 --register
VBoxManage modifyvm "Test-ISO" --memory 2048 --cpus 2
VBoxManage createhd --filename ~/VirtualBox\ VMs/Test-ISO/disk.vdi --size 10240
VBoxManage storagectl "Test-ISO" --name "SATA" --add sata
VBoxManage storageattach "Test-ISO" --storagectl "SATA" --port 0 --device 0 --type hdd --medium ~/VirtualBox\ VMs/Test-ISO/disk.vdi
VBoxManage storageattach "Test-ISO" --storagectl "SATA" --port 1 --device 0 --type dvddrive --medium ~/blockchain-iso/blockchain-node.iso

# Boot and test
VBoxManage startvm "Test-ISO"
```

### Using the ISO

1. **Burn to USB**:
   ```bash
   sudo dd if=blockchain-node.iso of=/dev/sdX bs=4M status=progress
   ```

2. **Boot from USB** on target machine

3. **Automatic installation** will:
   - Install Ubuntu
   - Install Docker
   - Clone blockchain repository
   - Build Docker images
   - Configure auto-start

---

## Option 3: Vagrant Box

Create a reproducible development environment using Vagrant.

### Step 1: Install Vagrant

```bash
# Install Vagrant
wget https://releases.hashicorp.com/vagrant/2.4.0/vagrant_2.4.0_linux_amd64.zip
unzip vagrant_2.4.0_linux_amd64.zip
sudo mv vagrant /usr/local/bin/

# Install VirtualBox provider
sudo apt-get install virtualbox
```

### Step 2: Create Vagrantfile

```bash
cd ~/Blockchain/blockchain-java
```

Create `Vagrantfile`:

```ruby
# -*- mode: ruby -*-
# vi: set ft=ruby :

Vagrant.configure("2") do |config|
  # Base box
  config.vm.box = "ubuntu/jammy64"
  
  # VM configuration
  config.vm.provider "virtualbox" do |vb|
    vb.name = "blockchain-node"
    vb.memory = "4096"
    vb.cpus = 2
  end
  
  # Network configuration
  config.vm.network "forwarded_port", guest: 8000, host: 8000, host_ip: "127.0.0.1"
  config.vm.network "forwarded_port", guest: 8001, host: 8001, host_ip: "127.0.0.1"
  config.vm.network "forwarded_port", guest: 8002, host: 8002, host_ip: "127.0.0.1"
  config.vm.network "forwarded_port", guest: 6001, host: 6001, host_ip: "127.0.0.1"
  config.vm.network "forwarded_port", guest: 6002, host: 6002, host_ip: "127.0.0.1"
  config.vm.network "forwarded_port", guest: 6003, host: 6003, host_ip: "127.0.0.1"
  
  # Sync blockchain code
  config.vm.synced_folder ".", "/home/vagrant/blockchain"
  
  # Provisioning
  config.vm.provision "shell", inline: <<-SHELL
    # Update system
    apt-get update
    apt-get upgrade -y
    
    # Install Docker
    curl -fsSL https://get.docker.com -o get-docker.sh
    sh get-docker.sh
    
    # Install Docker Compose
    apt-get install -y docker-compose-plugin
    
    # Install utilities
    apt-get install -y git curl jq
    
    # Add vagrant user to docker group
    usermod -aG docker vagrant
    
    # Build blockchain
    cd /home/vagrant/blockchain
    docker-compose build
    
    # Create systemd service
    cat > /etc/systemd/system/blockchain.service << 'EOF'
[Unit]
Description=Blockchain Network
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/home/vagrant/blockchain
ExecStart=/usr/bin/docker-compose up -d
ExecStop=/usr/bin/docker-compose down
User=vagrant

[Install]
WantedBy=multi-user.target
EOF
    
    systemctl daemon-reload
    systemctl enable blockchain.service
    systemctl start blockchain.service
  SHELL
end
```

### Step 3: Multi-VM Setup (Optional)

For testing multi-PC setup:

```ruby
Vagrant.configure("2") do |config|
  # Seed node
  config.vm.define "seed" do |seed|
    seed.vm.box = "ubuntu/jammy64"
    seed.vm.hostname = "blockchain-seed"
    seed.vm.network "private_network", ip: "192.168.50.10"
    seed.vm.network "forwarded_port", guest: 8000, host: 8000
    seed.vm.network "forwarded_port", guest: 6001, host: 6001
    
    seed.vm.provider "virtualbox" do |vb|
      vb.memory = "2048"
      vb.cpus = 1
    end
    
    seed.vm.provision "shell", path: "scripts/provision-seed.sh"
  end
  
  # Peer node 1
  config.vm.define "peer1" do |peer|
    peer.vm.box = "ubuntu/jammy64"
    peer.vm.hostname = "blockchain-peer1"
    peer.vm.network "private_network", ip: "192.168.50.11"
    peer.vm.network "forwarded_port", guest: 8000, host: 8001
    peer.vm.network "forwarded_port", guest: 6001, host: 6002
    
    peer.vm.provider "virtualbox" do |vb|
      vb.memory = "2048"
      vb.cpus = 1
    end
    
    peer.vm.provision "shell", path: "scripts/provision-peer.sh"
  end
  
  # Peer node 2
  config.vm.define "peer2" do |peer|
    peer.vm.box = "ubuntu/jammy64"
    peer.vm.hostname = "blockchain-peer2"
    peer.vm.network "private_network", ip: "192.168.50.12"
    peer.vm.network "forwarded_port", guest: 8000, host: 8002
    peer.vm.network "forwarded_port", guest: 6001, host: 6003
    
    peer.vm.provider "virtualbox" do |vb|
      vb.memory = "2048"
      vb.cpus = 1
    end
    
    peer.vm.provision "shell", path: "scripts/provision-peer.sh"
  end
end
```

### Step 4: Create Provisioning Scripts

**scripts/provision-seed.sh**:

```bash
#!/bin/bash
set -e

# Install Docker
curl -fsSL https://get.docker.com | sh
apt-get install -y docker-compose-plugin jq curl

# Build and start seed node
cd /vagrant
docker-compose -f docker-compose.seed.yml up -d
```

**scripts/provision-peer.sh**:

```bash
#!/bin/bash
set -e

# Install Docker
curl -fsSL https://get.docker.com | sh
apt-get install -y docker-compose-plugin jq curl

# Build and start peer node
cd /vagrant
docker-compose -f docker-compose.peer.yml up -d
```

### Step 5: Package Vagrant Box

```bash
# Create base box
vagrant up
vagrant package --output blockchain-node.box

# Add to Vagrant
vagrant box add blockchain-node blockchain-node.box

# Distribute the .box file
```

### Using Vagrant Box

```bash
# Initialize
vagrant init blockchain-node

# Start VM
vagrant up

# SSH into VM
vagrant ssh

# Stop VM
vagrant halt

# Destroy VM
vagrant destroy
```

---

## Testing the Environment

### Quick Test

```bash
# Wait for services to start
sleep 60

# Test health endpoints
curl http://localhost:8000/health
curl http://localhost:8001/health
curl http://localhost:8002/health

# Run transaction test
./test_transaction.sh
```

### Performance Test

```bash
# Install Apache Bench
sudo apt-get install apache2-utils

# Test API throughput
ab -n 1000 -c 10 http://localhost:8000/api/network/status
```

### Network Test

```bash
# Test P2P connectivity
nc -zv localhost 6001
nc -zv localhost 6002
nc -zv localhost 6003

# Check peer connections
curl http://localhost:8000/api/peers | jq '.'
```

---

## Comparison Matrix

| Feature | VM | ISO | Vagrant |
|---------|----|----|---------|
| **Ease of Creation** | ⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐⭐ |
| **Portability** | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| **Reproducibility** | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **Size** | Large (5-10GB) | Medium (2-4GB) | Small (1-2GB) |
| **Boot Time** | Fast (30s) | Medium (2-3min) | Fast (30s) |
| **Best For** | Demos | Physical deployment | Development |

---

## Next Steps

1. Choose the approach that fits your needs
2. Follow the step-by-step instructions
3. Test the environment thoroughly
4. Distribute to your team or deployment targets

---

**Last Updated**: February 1, 2026
