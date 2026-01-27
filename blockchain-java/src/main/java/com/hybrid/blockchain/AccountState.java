package com.hybrid.blockchain;

import com.hybrid.blockchain.identity.SSIManager;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import com.hybrid.blockchain.privacy.PrivateDataManager;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public class AccountState {
    private final Map<String, Account> state;
    private final SSIManager ssiManager;
    private final DeviceLifecycleManager lifecycleManager;
    private final PrivateDataManager privateDataManager;

    public AccountState() {
        this.state = new HashMap<>();
        this.ssiManager = new SSIManager();
        this.lifecycleManager = new DeviceLifecycleManager(ssiManager);
        this.privateDataManager = new PrivateDataManager();
    }

    public AccountState(Map<String, Account> obj) {
        this.state = new HashMap<>(obj);
        this.ssiManager = new SSIManager();
        this.lifecycleManager = new DeviceLifecycleManager(ssiManager);
        this.privateDataManager = new PrivateDataManager();
    }

    public static AccountState fromMap(Map<String, Object> raw) {
        Map<String, Account> map = new HashMap<>();
        if (raw == null)
            return new AccountState(map);
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> accMap = (Map<String, Object>) e.getValue();
            long balance = Utils.safeLong(accMap.get("balance"));
            long nonce = Utils.safeLong(accMap.get("nonce"));
            map.put(e.getKey(), new Account(balance, nonce));
        }
        return new AccountState(map);
    }

    public Map<String, Object> toJSON() {
        Map<String, Object> json = new HashMap<>();
        for (Map.Entry<String, Account> entry : state.entrySet()) {
            Map<String, Object> accJson = new HashMap<>();
            accJson.put("balance", entry.getValue().getBalance());
            accJson.put("nonce", entry.getValue().getNonce());
            json.put(entry.getKey(), accJson);
        }
        return json;
    }

    public long getBalance(String addr) {
        Account acc = state.get(addr);
        return acc != null ? acc.getBalance() : 0;
    }

    public long getNonce(String addr) {
        Account acc = state.get(addr);
        return acc != null ? acc.getNonce() : 0;
    }

    public void ensure(String addr) {
        state.putIfAbsent(addr, new Account(0, 0));
    }

    public void credit(String addr, long amount) {
        ensure(addr);
        state.get(addr).credit(amount);
    }

    public void debit(String addr, long amount) throws Exception {
        ensure(addr);
        Account acc = state.get(addr);
        if (amount < 0) {
            throw new Exception("Invalid amount: cannot debit negative amount");
        }
        if (acc.getBalance() < amount) {
            throw new Exception("Insufficient balance");
        }
        acc.debit(amount);
    }

    public void incrementNonce(String addr) {
        ensure(addr);
        state.get(addr).incrementNonce();
    }

    public void setNonce(String addr, long n) {
        ensure(addr);
        state.get(addr).setNonce(n);
    }

    public ContractState getAccountStorage(String addr) {
        ensure(addr);
        return state.get(addr).getStorage();
    }

    public java.util.Set<Capability> getAccountCapabilities(String addr) {
        ensure(addr);
        return state.get(addr).getCapabilities();
    }

    // SSI Management
    public SSIManager getSSIManager() {
        return ssiManager;
    }

    // Device Lifecycle Management
    public DeviceLifecycleManager getLifecycleManager() {
        return lifecycleManager;
    }

    // Update blockchain height for lifecycle manager
    public void setBlockHeight(long height) {
        lifecycleManager.setCurrentBlockHeight(height);
    }

    // Private Data Management
    public PrivateDataManager getPrivateDataManager() {
        return privateDataManager;
    }

    public String calculateStateRoot() {
        return Crypto.bytesToHex(Crypto.hash(serializeCanonical()));
    }

    public byte[] serializeCanonical() {
        // Collect and sort addresses for determinism
        List<String> addresses = new java.util.ArrayList<>(state.keySet());
        java.util.Collections.sort(addresses);

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1024 * 1024); // Adjust as needed
        buf.order(java.nio.ByteOrder.BIG_ENDIAN);

        for (String addr : addresses) {
            byte[] addrBytes = addr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.putInt(addrBytes.length);
            buf.put(addrBytes);

            Account acc = state.get(addr);
            buf.putLong(acc.getBalance());
            buf.putLong(acc.getNonce());

            // Include contract storage in the hash
            byte[] storageRoot = HexUtils.decode(acc.getStorage().calculateRoot());
            buf.put(storageRoot);

            // Include capabilities in the hash
            java.util.Set<Capability> caps = acc.getCapabilities();
            buf.putInt(caps.size());
            java.util.List<Capability> sortedCaps = new java.util.ArrayList<>(caps);
            sortedCaps.sort((c1, c2) -> {
                int typeComp = c1.getType().compareTo(c2.getType());
                if (typeComp != 0)
                    return typeComp;
                return Long.compare(c1.getDeviceId(), c2.getDeviceId());
            });
            for (Capability cap : sortedCaps) {
                buf.putInt(cap.getType().ordinal());
                buf.putLong(cap.getDeviceId());
            }
        }

        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    public static class Account {
        private long balance;
        private long nonce;
        private final ContractState storage;
        private final java.util.Set<Capability> capabilities;

        public Account(long balance, long nonce) {
            this.balance = balance;
            this.nonce = nonce;
            this.storage = new ContractState();
            this.capabilities = new java.util.HashSet<>();
        }

        public long getBalance() {
            return balance;
        }

        public long getNonce() {
            return nonce;
        }

        public ContractState getStorage() {
            return storage;
        }

        public java.util.Set<Capability> getCapabilities() {
            return capabilities;
        }

        public void addCapability(Capability cap) {
            capabilities.add(cap);
        }

        public void credit(long amount) {
            balance += amount;
        }

        public void debit(long amount) {
            balance -= amount;
        }

        public void incrementNonce() {
            nonce += 1;
        }

        public void setNonce(long n) {
            nonce = n;
        }
    }
}
