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

/**
 * Manages all account state for HybridChain, including multi-token balances, nonces,
 * contract code/storage, capabilities, SSI, device lifecycle, and private data.
 *
 * <p>Each account's balance is stored in a {@code Map<String, Long> tokenBalances}
 * where the key {@code "native"} holds the native chain token. Additional token IDs
 * are used for custom tokens registered via {@link TokenRegistry}.
 */
public class AccountState {
    private Map<String, Account> state;
    private SSIManager ssiManager;
    private DeviceLifecycleManager lifecycleManager;
    private PrivateDataManager privateDataManager;
    private MerklePatriciaTrie mpt;

    public String calculateRoot() {
        return HexUtils.encode(mpt.getRootHash());
    }

    public AccountState() {
        this.state = new ConcurrentHashMap<>();
        this.mpt = new MerklePatriciaTrie();
        this.ssiManager = new SSIManager();
        this.lifecycleManager = new DeviceLifecycleManager(ssiManager);
        this.privateDataManager = new PrivateDataManager();
    }

    public java.util.Set<String> getAccountAddresses() {
        return state.keySet();
    }

    /**
     * Returns an unmodifiable set of all account addresses in state.
     * @return immutable set of address strings
     */
    public java.util.Set<String> getAllAddresses() {
        return Collections.unmodifiableSet(state.keySet());
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

    @SuppressWarnings("unchecked")
    public static AccountState fromMap(Map<String, Object> raw) {
        Map<String, Account> map = new HashMap<>();
        if (raw == null)
            return new AccountState(map);

        Map<String, Object> accounts = (Map<String, Object>) raw.get("accounts");
        if (accounts != null) {
            for (Map.Entry<String, Object> e : accounts.entrySet()) {
                Map<String, Object> accMap = (Map<String, Object>) e.getValue();

                // Multi-token balances
                Map<String, Long> tokenBalances = new HashMap<>();
                if (accMap.containsKey("tokenBalances")) {
                    Map<String, Object> tbRaw = (Map<String, Object>) accMap.get("tokenBalances");
                    for (Map.Entry<String, Object> tb : tbRaw.entrySet()) {
                        tokenBalances.put(tb.getKey(), Utils.safeLong(tb.getValue()));
                    }
                } else if (accMap.containsKey("balance")) {
                    // Backward-compat: load legacy single balance as native
                    tokenBalances.put("native", Utils.safeLong(accMap.get("balance")));
                }

                long nonce = Utils.safeLong(accMap.get("nonce"));
                byte[] code = null;
                if (accMap.containsKey("code")) {
                    code = HexUtils.decode((String) accMap.get("code"));
                }
                Account acc = new Account(tokenBalances, nonce, code);

                // Load ABI
                if (accMap.containsKey("abi")) {
                    acc.setAbi(((String) accMap.get("abi")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }

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

        if (raw.containsKey("ssi")) {
            SSIManager ssi = SSIManager.fromMap((Map<String, Object>) raw.get("ssi"));
            accountState.ssiManager.restore(ssi);
        }

        if (raw.containsKey("lifecycle")) {
            DeviceLifecycleManager lifecycle = DeviceLifecycleManager.fromMap(
                    (Map<String, Object>) raw.get("lifecycle"), accountState.ssiManager);
            accountState.lifecycleManager.restore(lifecycle);
        }

        if (raw.containsKey("privateData")) {
            PrivateDataManager privateData = PrivateDataManager.fromMap((Map<String, Object>) raw.get("privateData"));
            accountState.privateDataManager.restore(privateData);
        }

        return accountState;
    }

    public Map<String, Object> toJSON() {
        Map<String, Object> json = new HashMap<>();

        Map<String, Object> accounts = new HashMap<>();
        for (Map.Entry<String, Account> entry : state.entrySet()) {
            Account acc = entry.getValue();
            Map<String, Object> accJson = new HashMap<>();

            // Multi-token balances
            Map<String, Long> tbCopy = new HashMap<>(acc.getTokenBalances());
            accJson.put("tokenBalances", tbCopy);
            // Keep legacy "balance" field for tools that read it directly
            accJson.put("balance", acc.getBalance());

            accJson.put("nonce", acc.getNonce());
            if (acc.getCode() != null) {
                accJson.put("code", HexUtils.bytesToHex(acc.getCode()));
            }
            if (acc.getAbi() != null) {
                accJson.put("abi", new String(acc.getAbi(), java.nio.charset.StandardCharsets.UTF_8));
            }

            Map<String, Long> storage = new HashMap<>();
            for (Map.Entry<Long, Long> se : acc.getStorage().getStorage().entrySet()) {
                storage.put(String.valueOf(se.getKey()), se.getValue());
            }
            accJson.put("storage", storage);

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
        json.put("ssi", ssiManager.toJSON());
        json.put("lifecycle", lifecycleManager.toJSON());
        json.put("privateData", privateDataManager.toJSON());

        return json;
    }

    // ─── Native balance helpers (backward-compat) ─────────────────────────────

    /**
     * Returns the native token balance of the given address.
     *
     * @param addr the account address
     * @return native token balance
     */
    public long getBalance(String addr) {
        Account acc = state.get(addr);
        return acc != null ? acc.getBalance() : 0;
    }

    /**
     * Credits the native token to the given address by the specified amount.
     *
     * @param addr   the account address
     * @param amount the amount to credit
     */
    public void credit(String addr, long amount) {
        if (amount < 0) throw new IllegalArgumentException("Invalid amount: cannot credit negative amount");
        ensure(addr);
        Account acc = state.get(addr);
        acc.credit(amount);
        updateMpt(addr, acc);
    }

    /**
     * Debits the native token from the given address by the specified amount.
     *
     * @param addr   the account address
     * @param amount the amount to debit
     * @throws IllegalArgumentException if the balance is insufficient or amount is negative
     */
    public void debit(String addr, long amount) {
        ensure(addr);
        Account acc = state.get(addr);
        if (amount < 0) throw new IllegalArgumentException("Invalid amount: cannot debit negative amount");
        if (acc.getBalance() < amount) throw new IllegalArgumentException("Insufficient balance for " + addr + ": " + acc.getBalance() + " < " + amount);
        acc.debit(amount);
        updateMpt(addr, acc);
    }

    // ─── Multi-token helpers ──────────────────────────────────────────────────

    /**
     * Credits a specific token to an address.
     *
     * @param addr    the account address
     * @param tokenId the token identifier ("native" for the chain token)
     * @param amount  the amount to credit
     */
    public void creditToken(String addr, String tokenId, long amount) {
        ensure(addr);
        Account acc = state.get(addr);
        acc.creditToken(tokenId, amount);
        updateMpt(addr, acc);
    }

    /**
     * Sets a specific token balance for an address.
     *
     * @param addr    the account address
     * @param tokenId the token identifier
     * @param amount  the balance to set
     */
    public void setTokenBalance(String addr, String tokenId, long amount) {
        ensure(addr);
        Account acc = state.get(addr);
        acc.setTokenBalance(tokenId, amount);
        updateMpt(addr, acc);
    }

    /**
     * Debits a specific token from an address.
     *
     * @param addr    the account address
     * @param tokenId the token identifier
     * @param amount  the amount to debit
     * @throws Exception if the token balance is insufficient
     */
    public void debitToken(String addr, String tokenId, long amount) throws Exception {
        ensure(addr);
        Account acc = state.get(addr);
        long bal = acc.getTokenBalance(tokenId);
        if (bal < amount) {
            throw new Exception("Insufficient " + tokenId + " balance for " + addr + ": " + bal + " < " + amount);
        }
        acc.debitToken(tokenId, amount);
        updateMpt(addr, acc);
    }

    /**
     * Returns the balance of a specific token for an address.
     *
     * @param addr    the account address
     * @param tokenId the token identifier
     * @return the token balance, or 0 if not found
     */
    public long getTokenBalance(String addr, String tokenId) {
        Account acc = state.get(addr);
        return acc != null ? acc.getTokenBalance(tokenId) : 0;
    }

    /**
     * Returns all token balances for an address.
     *
     * @param addr the account address
     * @return a copy of the token balance map, or empty map if address not found
     */
    public Map<String, Long> getAllTokenBalances(String addr) {
        Account acc = state.get(addr);
        return acc != null ? new HashMap<>(acc.getTokenBalances()) : new HashMap<>();
    }

    // ─── Other accessors ─────────────────────────────────────────────────────

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
        state.computeIfAbsent(addr, k -> {
            Account acc = new Account(0, 0);
            updateMpt(addr, acc);
            return acc;
        });
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

    public void setAccountReputation(String addr, double reputation) {
        ensure(addr);
        Account acc = state.get(addr);
        acc.setReputation(reputation);
        updateMpt(addr, acc);
    }

    public void setCode(String addr, byte[] code) {
        ensure(addr);
        Account acc = state.get(addr);
        acc.setCode(code);
        updateMpt(addr, acc);
    }

    public void setAbi(String addr, byte[] abi) {
        ensure(addr);
        Account acc = state.get(addr);
        acc.setAbi(abi);
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

    public SSIManager getSSIManager() { return ssiManager; }

    public DeviceLifecycleManager getLifecycleManager() { return lifecycleManager; }

    /**
     * Wires a Storage instance into the lifecycle manager so that reputation scores
     * are persisted on-chain rather than kept only in memory.
     * Call this once after construction when storage is available.
     *
     * @param storage the blockchain storage instance
     */
    public void setStorage(com.hybrid.blockchain.Storage storage) {
        this.lifecycleManager.setStorage(storage);
    }

    public void setBlockHeight(long height) {
        lifecycleManager.setCurrentBlockHeight(height);
    }

    public long getBlockHeight() {
        return lifecycleManager.getCurrentBlockHeight();
    }

    public PrivateDataManager getPrivateDataManager() { return privateDataManager; }

    /**
     * Calculates the Merkle Patricia Trie root hash of the current state.
     *
     * @return the state root hash as a hex string
     */
    public String calculateStateRoot() {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            
            byte[] accountRoot = mpt.getRootHash();
            byte[] ssiBytes    = objectMapper.writeValueAsBytes(ssiManager.toJSON());
            byte[] ssiHash     = Crypto.hash(ssiBytes);
            byte[] lcBytes     = objectMapper.writeValueAsBytes(lifecycleManager.toJSON());
            byte[] lcHash      = Crypto.hash(lcBytes);

            byte[] combined = new byte[96];
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
     * @param address the account address
     * @return a list of serialized MPT nodes for the account
     */
    public java.util.List<byte[]> getAccountProof(String address) {
        return mpt.getAccountProof(address.getBytes());
    }

    /**
     * Generates a compact Merkle Proof as a single byte array.
     *
     * @param address the account address
     * @return a single byte array containing all proof nodes
     */
    public byte[] getCompactAccountProof(String address) {
        return mpt.getCompactAccountProof(address.getBytes());
    }

    public AccountState cloneState() {
        return AccountState.fromMap(this.toJSON());
    }

    public DeviceLifecycleManager getDeviceLifecycleManager() {
        return lifecycleManager;
    }

    /**
     * Merges another AccountState delta into this one.
     *
     * <p>Merge semantics:
     * <ul>
     *   <li>If the address exists in both states: the delta's balance is ADDED to this state.
     *       Nonce is updated to the maximum of both.
     *   <li>If the address only exists in the delta: it is copied into this state as-is.
     * </ul>
     *
     * <p>This method is used to commit successful smart-contract executions and
     * block-simulation side effects back to the canonical state.
     */
    public void merge(AccountState other) {
        for (Map.Entry<String, Account> entry : other.state.entrySet()) {
            String addr = entry.getKey();
            Account otherAccount = entry.getValue();

            if (this.state.containsKey(addr)) {
                Account thisAccount = this.state.get(addr);
                
                // OVERWRITE balances from the sim state (sim state started as a clone)
                Map<String, Long> newsBals = otherAccount.getTokenBalances();
                for (Map.Entry<String, Long> tokenEntry : newsBals.entrySet()) {
                    thisAccount.setTokenBalance(tokenEntry.getKey(), tokenEntry.getValue());
                }
                
                // Sync nonces
                thisAccount.setNonce(otherAccount.getNonce());
                
                // Sync code if new
                if (thisAccount.getCode() == null && otherAccount.getCode() != null) {
                    thisAccount.setCode(otherAccount.getCode());
                }

                // Sync storage
                if (otherAccount.getStorage() != null) {
                    thisAccount.getStorage().putAll(otherAccount.getStorage().getStorage());
                }

                // Sync capabilities
                thisAccount.getCapabilities().clear();
                thisAccount.getCapabilities().addAll(otherAccount.getCapabilities());

            } else {
                // New account: clone it into this state
                Map<String, Long> balCopy = new HashMap<>(otherAccount.getTokenBalances());
                Account newAccount = new Account(balCopy, otherAccount.getNonce(), otherAccount.getCode());
                if (otherAccount.getStorage() != null) {
                    newAccount.getStorage().putAll(otherAccount.getStorage().getStorage());
                }
                newAccount.getCapabilities().addAll(otherAccount.getCapabilities());
                this.state.put(addr, newAccount);
            }
        }

        // Sync sub-managers (SSI, Lifecycle, PrivateData) via RESTORE to maintain references if they are already wired
        this.ssiManager.restore(other.ssiManager);
        this.lifecycleManager.restore(other.lifecycleManager);
        this.privateDataManager.restore(other.privateDataManager);

        // Refresh MPT for all modified accounts
        for (String addr : other.state.keySet()) {
            Account acc = this.state.get(addr);
            if (acc != null) updateMpt(addr, acc);
        }
    }

    private void updateMpt(String addr, Account acc) {
        byte[] serialized = serializeAccount(acc);
        mpt.put(addr, serialized);
    }

    private byte[] serializeAccount(Account acc) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);

            dos.writeLong(acc.getBalance());
            dos.writeLong(acc.getNonce());
            dos.writeDouble(acc.getReputation()); // FIX 4: persistent reputation in state root

            byte[] storageRoot = HexUtils.decode(acc.getStorage().calculateRoot());
            dos.writeInt(storageRoot.length);
            dos.write(storageRoot);

            byte[] code = acc.getCode();
            if (code == null) {
                dos.writeInt(0);
            } else {
                dos.writeInt(code.length);
                dos.write(code);
            }

            java.util.Set<Capability> caps = acc.getCapabilities();
            dos.writeInt(caps.size());
            java.util.List<Capability> sortedCaps = new java.util.ArrayList<>(caps);
            sortedCaps.sort((c1, c2) -> {
                int typeComp = c1.getType().compareTo(c2.getType());
                if (typeComp != 0) return typeComp;
                return Long.compare(c1.getDeviceId(), c2.getDeviceId());
            });
            for (Capability cap : sortedCaps) {
                dos.writeInt(cap.getType().ordinal());
                dos.writeLong(cap.getDeviceId());
            }

            dos.flush();
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }

    // ─── Account inner class ──────────────────────────────────────────────────

    /**
     * Represents a single account's state, including multi-token balances,
     * nonce, contract code, contract storage, and capabilities.
     */
    public static class Account {
        /** Token balances: "native" key for the chain token, other keys for custom tokens. */
        private final Map<String, Long> tokenBalances;
        private long nonce;
        private byte[] code;
        private byte[] abi;
        private final ContractState storage;
        private final java.util.Set<Capability> capabilities;
        private double reputation = 0.5;

        public Account(long balance, long nonce) {
            this(balance, nonce, null);
        }

        public Account(long balance, long nonce, byte[] code) {
            this.tokenBalances = new ConcurrentHashMap<>();
            this.tokenBalances.put("native", balance);
            this.nonce = nonce;
            this.code = code;
            this.storage = new ContractState();
            this.capabilities = Collections.synchronizedSet(new java.util.HashSet<>());
        }

        public Account(Map<String, Long> tokenBalances, long nonce, byte[] code) {
            this.tokenBalances = new ConcurrentHashMap<>(tokenBalances);
            this.nonce = nonce;
            this.code = code;
            this.storage = new ContractState();
            this.capabilities = Collections.synchronizedSet(new java.util.HashSet<>());
        }

        public synchronized double getReputation() { return reputation; }
        public synchronized void setReputation(double r) { 
            this.reputation = r; 
        }

        /** Returns the native token balance. */
        public synchronized long getBalance() {
            return tokenBalances.getOrDefault("native", 0L);
        }

        /** Returns the balance of a specific token. */
        public synchronized long getTokenBalance(String tokenId) {
            return tokenBalances.getOrDefault(tokenId, 0L);
        }

        /** Returns a copy of all token balances. */
        public synchronized Map<String, Long> getTokenBalances() {
            return new HashMap<>(tokenBalances);
        }

        public synchronized long getNonce() { return nonce; }

        public ContractState getStorage() { return storage; }

        public java.util.Set<Capability> getCapabilities() { return capabilities; }

        public void addCapability(Capability cap) { capabilities.add(cap); }

        /** Credits the native token. */
        public synchronized void credit(long amount) {
            tokenBalances.merge("native", amount, (a, b) -> a + b);
        }

        /** Sets a specific token balance. */
        public synchronized void setTokenBalance(String tokenId, long amount) {
            tokenBalances.put(tokenId, amount);
        }

        /** Credits a specific token. */
        public synchronized void creditToken(String tokenId, long amount) {
            tokenBalances.merge(tokenId, amount, (a, b) -> a + b);
        }

        /** Debits the native token (no balance check). */
        public synchronized void debit(long amount) {
            tokenBalances.merge("native", -amount, (a, b) -> a + b);
        }

        /** Debits a specific token (no balance check). */
        public synchronized void debitToken(String tokenId, long amount) {
            tokenBalances.merge(tokenId, -amount, (a, b) -> a + b);
        }

        public synchronized void incrementNonce() { nonce += 1; }

        public synchronized void setNonce(long n) { nonce = n; }

        public synchronized byte[] getCode() { return code; }

        public synchronized void setCode(byte[] code) { this.code = code; }

        public synchronized byte[] getAbi() { return abi; }

        public synchronized void setAbi(byte[] abi) { this.abi = abi; }
    }
}
