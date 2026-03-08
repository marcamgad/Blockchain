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
    private final MerklePatriciaTrie mpt;

    public AccountState() {
        this.state = new HashMap<>();
        this.mpt = new MerklePatriciaTrie();
        this.ssiManager = new SSIManager();
        this.lifecycleManager = new DeviceLifecycleManager(ssiManager);
        this.privateDataManager = new PrivateDataManager();
    }

    public AccountState(Map<String, Account> obj) {
        this.state = new HashMap<>(obj);
        this.mpt = new MerklePatriciaTrie();
        for (Map.Entry<String, Account> entry : obj.entrySet()) {
            updateMpt(entry.getKey(), entry.getValue());
        }
        this.ssiManager = new SSIManager();
        this.lifecycleManager = new DeviceLifecycleManager(ssiManager);
        this.privateDataManager = new PrivateDataManager();
    }

    public static AccountState fromMap(Map<String, Object> raw) {
        Map<String, Account> map = new HashMap<>();
        if (raw == null)
            return new AccountState(map);

        // Load account balances and nonces
        Map<String, Object> accounts = (Map<String, Object>) raw.get("accounts");
        if (accounts != null) {
            for (Map.Entry<String, Object> e : accounts.entrySet()) {
                Map<String, Object> accMap = (Map<String, Object>) e.getValue();
                long balance = Utils.safeLong(accMap.get("balance"));
                long nonce = Utils.safeLong(accMap.get("nonce"));
                map.put(e.getKey(), new Account(balance, nonce));
            }
        }

        AccountState accountState = new AccountState(map);

        // Load SSI state
        if (raw.containsKey("ssi")) {
            SSIManager ssi = SSIManager.fromMap((Map<String, Object>) raw.get("ssi"));
            accountState.ssiManager.restore(ssi);
        }

        // Load Lifecycle state
        if (raw.containsKey("lifecycle")) {
            DeviceLifecycleManager lifecycle = DeviceLifecycleManager.fromMap((Map<String, Object>) raw.get("lifecycle"), accountState.ssiManager);
            accountState.lifecycleManager.restore(lifecycle);
        }

        return accountState;
    }

    public Map<String, Object> toJSON() {
        Map<String, Object> json = new HashMap<>();
        
        // Serialize accounts
        Map<String, Object> accounts = new HashMap<>();
        for (Map.Entry<String, Account> entry : state.entrySet()) {
            Map<String, Object> accJson = new HashMap<>();
            accJson.put("balance", entry.getValue().getBalance());
            accJson.put("nonce", entry.getValue().getNonce());
            accounts.put(entry.getKey(), accJson);
        }
        json.put("accounts", accounts);

        // Serialize SSI Manager
        json.put("ssi", ssiManager.toJSON());

        // Serialize Device Lifecycle Manager
        json.put("lifecycle", lifecycleManager.toJSON());

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
        Account acc = state.get(addr);
        acc.credit(amount);
        updateMpt(addr, acc);
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
        updateMpt(addr, acc);
    }

    public void incrementNonce(String addr) {
        ensure(addr);
        Account acc = state.get(addr);
        acc.incrementNonce();
        updateMpt(addr, acc);
    }

    public void setNonce(String addr, long n) {
        ensure(addr);
        Account acc = state.get(addr);
        acc.setNonce(n);
        updateMpt(addr, acc);
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
        // Includes Account map, SSI, and Lifecycle roots in the future
        // For now, it returns the MPT root of the Accounts
        return Crypto.bytesToHex(mpt.getRootHash());
    }

    private void updateMpt(String addr, Account acc) {
        byte[] serialized = serializeAccount(acc);
        mpt.put(addr, serialized);
    }

    private byte[] serializeAccount(Account acc) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1024);
        buf.order(java.nio.ByteOrder.BIG_ENDIAN);
        buf.putLong(acc.getBalance());
        buf.putLong(acc.getNonce());
        
        byte[] storageRoot = HexUtils.decode(acc.getStorage().calculateRoot());
        buf.putInt(storageRoot.length);
        buf.put(storageRoot);

        java.util.Set<Capability> caps = acc.getCapabilities();
        buf.putInt(caps.size());
        java.util.List<Capability> sortedCaps = new java.util.ArrayList<>(caps);
        sortedCaps.sort((c1, c2) -> {
            int typeComp = c1.getType().compareTo(c2.getType());
            if (typeComp != 0) return typeComp;
            return Long.compare(c1.getDeviceId(), c2.getDeviceId());
        });
        for (Capability cap : sortedCaps) {
            buf.putInt(cap.getType().ordinal());
            buf.putLong(cap.getDeviceId());
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
