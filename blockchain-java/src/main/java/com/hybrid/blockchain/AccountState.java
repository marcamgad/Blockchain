package com.hybrid.blockchain;

import com.hybrid.blockchain.identity.SSIManager;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import com.hybrid.blockchain.privacy.PrivateDataManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

public class AccountState {
    private final Map<String, Account> state;
    private final SSIManager ssiManager;
    private final DeviceLifecycleManager lifecycleManager;
    private final PrivateDataManager privateDataManager;
    private final MerklePatriciaTrie mpt;

    public AccountState() {
        this.state = new ConcurrentHashMap<>();
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
                byte[] code = null;
                if (accMap.containsKey("code")) {
                    code = HexUtils.decode((String) accMap.get("code"));
                }
                Account acc = new Account(balance, nonce, code);
                
                // Load Storage
                if (accMap.containsKey("storage")) {
                    Map<String, Object> storageMap = (Map<String, Object>) accMap.get("storage");
                    for (Map.Entry<String, Object> se : storageMap.entrySet()) {
                        acc.getStorage().put(Long.parseLong(se.getKey()), Utils.safeLong(se.getValue()));
                    }
                }
                
                // Load Capabilities
                if (accMap.containsKey("capabilities")) {
                    List<Map<String, Object>> capsList = (List<Map<String, Object>>) accMap.get("capabilities");
                    for (Map<String, Object> capMap : capsList) {
                        Capability.Type type = Capability.Type.valueOf((String) capMap.get("type"));
                        long devId = Utils.safeLong(capMap.get("deviceId"));
                        acc.addCapability(new Capability(type, devId));
                    }
                }
                
                map.put(e.getKey(), acc);
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

        // Load Private Data Manager state
        if (raw.containsKey("privateData")) {
            PrivateDataManager privateData = PrivateDataManager.fromMap((Map<String, Object>) raw.get("privateData"));
            accountState.privateDataManager.restore(privateData);
        }

        return accountState;
    }

    public Map<String, Object> toJSON() {
        Map<String, Object> json = new HashMap<>();
        
        // Serialize accounts
        Map<String, Object> accounts = new HashMap<>();
        for (Map.Entry<String, Account> entry : state.entrySet()) {
            Account acc = entry.getValue();
            Map<String, Object> accJson = new HashMap<>();
            accJson.put("balance", acc.getBalance());
            accJson.put("nonce", acc.getNonce());
            if (acc.getCode() != null) {
                accJson.put("code", HexUtils.bytesToHex(acc.getCode()));
            }
            
            // Serialize Storage
            Map<String, Long> storage = new HashMap<>();
            for (Map.Entry<Long, Long> se : acc.getStorage().getStorage().entrySet()) {
                storage.put(String.valueOf(se.getKey()), se.getValue());
            }
            accJson.put("storage", storage);
            
            // Serialize Capabilities
            List<Map<String, Object>> caps = new ArrayList<>();
            for (Capability cap : acc.getCapabilities()) {
                Map<String, Object> capMap = new HashMap<>();
                capMap.put("type", cap.getType().name());
                capMap.put("deviceId", cap.getDeviceId());
                caps.add(capMap);
            }
            accJson.put("capabilities", caps);
            
            accounts.put(entry.getKey(), accJson);
        }
        json.put("accounts", accounts);

        // Serialize SSI Manager
        json.put("ssi", ssiManager.toJSON());

        // Serialize Device Lifecycle Manager
        json.put("lifecycle", lifecycleManager.toJSON());

        // Serialize Private Data Manager
        json.put("privateData", privateDataManager.toJSON());

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

    public Account getAccount(String addr) {
        return state.get(addr);
    }

    public byte[] getSerializedAccount(String addr) {
        Account acc = getAccount(addr);
        return acc != null ? serializeAccount(acc) : null;
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

    public void putStorage(String addr, long key, long value) {
        ensure(addr);
        Account acc = state.get(addr);
        acc.getStorage().put(key, value);
        updateMpt(addr, acc);
    }

    public void addCapability(String addr, Capability cap) {
        ensure(addr);
        Account acc = state.get(addr);
        acc.addCapability(cap);
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

    /**
     * Calculates the Merkle Patricia Trie root hash of the current state.
     * 
     * @return The state root hash as a hex string.
     */
    public String calculateStateRoot() {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            byte[] accountRoot = mpt.getRootHash();                           // 32 bytes
            byte[] ssiBytes    = objectMapper.writeValueAsBytes(ssiManager.toJSON());
            byte[] ssiHash     = Crypto.hash(ssiBytes);                       // 32 bytes
            byte[] lcBytes     = objectMapper.writeValueAsBytes(lifecycleManager.toJSON());
            byte[] lcHash      = Crypto.hash(lcBytes);                        // 32 bytes
            
            byte[] combined    = new byte[96];
            System.arraycopy(accountRoot, 0, combined,  0, 32);
            System.arraycopy(ssiHash,     0, combined, 32, 32);
            System.arraycopy(lcHash,      0, combined, 64, 32);
            
            return Crypto.bytesToHex(Crypto.hash(combined));
        } catch (Exception e) {
            throw new RuntimeException("State root calculation failed", e);
        }
    }

    /**
     * Generates a Merkle Proof for an account address.
     * 
     * @param address The account address.
     * @return A list of serialized MPT nodes for the account.
     */
    public java.util.List<byte[]> getAccountProof(String address) {
        return mpt.getAccountProof(address.getBytes());
    }

    /**
     * Generates a compact Merkle Proof for an account address as a single byte array.
     * 
     * @param address The account address.
     * @return A single byte array containing all nodes in the proof.
     */
    public byte[] getCompactAccountProof(String address) {
        return mpt.getCompactAccountProof(address.getBytes());
    }
    
    public AccountState cloneState() {
        return AccountState.fromMap(this.toJSON());
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

        byte[] code = acc.getCode();
        if (code == null) {
            buf.putInt(0);
        } else {
            buf.putInt(code.length);
            buf.put(code);
        }

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
        private byte[] code;
        private final ContractState storage;
        private final java.util.Set<Capability> capabilities;

        public Account(long balance, long nonce) {
            this(balance, nonce, null);
        }

        public Account(long balance, long nonce, byte[] code) {
            this.balance = balance;
            this.nonce = nonce;
            this.code = code;
            this.storage = new ContractState();
            this.capabilities = Collections.synchronizedSet(new java.util.HashSet<>());
        }

        public synchronized long getBalance() {
            return balance;
        }

        public synchronized long getNonce() {
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

        public synchronized void credit(long amount) {
            balance += amount;
        }

        public synchronized void debit(long amount) {
            balance -= amount;
        }

        public synchronized void incrementNonce() {
            nonce += 1;
        }

        public synchronized void setNonce(long n) {
            nonce = n;
        }

        public synchronized byte[] getCode() {
            return code;
        }

        public synchronized void setCode(byte[] code) {
            this.code = code;
        }
    }
}
