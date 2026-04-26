package com.hybrid.blockchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.iq80.leveldb.*;
import static org.fusesource.leveldbjni.JniDBFactory.*;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

public class Storage implements AutoCloseable {
    public static class Checkpoint {
        private int blockHeight;
        private String blockHash;
        private String stateRoot;
        private String utxoRoot;

        public Checkpoint() {}
        public Checkpoint(int blockHeight, String blockHash, String stateRoot, String utxoRoot) {
            this.blockHeight = blockHeight;
            this.blockHash = blockHash;
            this.stateRoot = stateRoot;
            this.utxoRoot = utxoRoot;
        }
        public int getBlockHeight() { return blockHeight; }
        public String getBlockHash() { return blockHash; }
        public String getStateRoot() { return stateRoot; }
        public String getUtxoRoot() { return utxoRoot; }
        public void setBlockHeight(int blockHeight) { this.blockHeight = blockHeight; }
        public void setBlockHash(String blockHash) { this.blockHash = blockHash; }
        public void setStateRoot(String stateRoot) { this.stateRoot = stateRoot; }
        public void setUtxoRoot(String utxoRoot) { this.utxoRoot = utxoRoot; }
    }


    private final DB db;
    private final ObjectMapper mapper;
    private final Map<String, Object> cache;
    private final SecretKeySpec aesKey;
    private final String dbPath;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128; // in bits
    private final SecureRandom secureRandom = new SecureRandom();

    public Storage(String dbPath, byte[] aesKeyBytes) throws IOException {
        if (aesKeyBytes.length != 16 && aesKeyBytes.length != 24 && aesKeyBytes.length != 32) {
            throw new IllegalArgumentException("AES key must be 16, 24, or 32 bytes");
        }

        this.mapper = new ObjectMapper();
        this.cache = Collections.synchronizedMap(new LinkedHashMap<String, Object>(512, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                return size() > 512;
            }
        });
        this.aesKey = new SecretKeySpec(aesKeyBytes, "AES");

        this.dbPath = dbPath != null ? dbPath : "data";
        File folder = new File(this.dbPath);
        folder.mkdirs();

        Options options = new Options();
        options.createIfMissing(true);
        this.db = factory.open(folder, options);
    }

    // Fallback constructor (dev / testing)
    public Storage(String dbPath) throws IOException {
      this(dbPath, Config.STORAGE_AES_KEY);
    }
    
    public String getDbPath() {
        return this.dbPath;
    }

    private byte[] encrypt(byte[] data) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, spec);
        byte[] ciphertext = cipher.doFinal(data);
        byte[] encrypted = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, encrypted, 0, iv.length);
        System.arraycopy(ciphertext, 0, encrypted, iv.length, ciphertext.length);
        return encrypted;
    }

    private byte[] decrypt(byte[] data) throws Exception {
        if (data == null || data.length < GCM_IV_LENGTH) throw new IllegalArgumentException("Invalid encrypted data");
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(data, 0, iv, 0, GCM_IV_LENGTH);
        byte[] ciphertext = new byte[data.length - GCM_IV_LENGTH];
        System.arraycopy(data, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, aesKey, spec);
        return cipher.doFinal(ciphertext);
    }

    public void put(String key, Object value) {
        if (key == null) return;
        try {
            cache.put(key, value);
            byte[] json = mapper.writeValueAsBytes(value);
            byte[] encrypted = encrypt(json);
            db.put(bytes(secureKey(key)), encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Failed to store encrypted value", e);
        }
    }

    public <T> T get(String key, Class<T> clazz) {
        if (key == null) return null;
        if (cache.containsKey(key)) {
            return clazz.cast(cache.get(key));
        }
        try {
            String sk = secureKey(key);
            if (sk == null) return null;
            byte[] encrypted = db.get(bytes(sk));
            if (encrypted == null) return null;
            byte[] decrypted = decrypt(encrypted);
            T value = mapper.readValue(decrypted, clazz);
            cache.put(key, value);
            return value;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load encrypted value", e);
        }
    }

    public void del(String key) {
        if (key == null) return;
        cache.remove(key);
        String sk = secureKey(key);
        if (sk != null) {
            db.delete(bytes(sk));
        }
    }

    public void saveBlock(String hash, Block block) {
        put("block:" + hash, block);
        put("height:" + block.getIndex(), hash);

        // Critical tip update; use sync write
        try {
            byte[] json = mapper.writeValueAsBytes(hash);
            byte[] encrypted = encrypt(json);
            WriteOptions syncOptions = new WriteOptions().sync(true);
            db.put(bytes(secureKey("chain:tip")), encrypted, syncOptions);
            cache.put("chain:tip", hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save chain tip with sync", e);
        }
    }

    /** Overload for tests that pass just the Block. */
    public void saveBlock(Block block) {
        saveBlock(block.getHash(), block);
    }

    public Block loadBlockByHash(String hash) {
        return get("block:" + hash, Block.class);
    }

    public Block loadBlockByHeight(int height) {
        String hash = get("height:" + height, String.class);
        return hash != null ? loadBlockByHash(hash) : null;
    }

    public String loadTipHash() {
        return get("chain:tip", String.class);
    }

    public void saveUTXO(Map<String, ?> obj) {
        put("utxo:set", obj);
    }

    public Map<String, Object> loadUTXO() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = get("utxo:set", Map.class);
        return result;
    }

    public void saveState(AccountState state) {
        put("state:account", state.toJSON());
    }

    public AccountState loadState() {
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = get("state:account", Map.class);
        if (raw == null) return new AccountState();
        return AccountState.fromMap(raw);
    }

    public void putMeta(String key, Object value) {
        put("meta:" + key, value);
    }

    public Object getMeta(String key) {
        // Return Object to preserve numeric types instead of forcing String
        return get("meta:" + key, Object.class);
    }

    /**
     * Get the current size of the in-memory cache.
     * Useful for monitoring memory usage and cache effectiveness.
     * 
     * @return the number of entries in the cache
     */
    public int getCacheSize() {
        return cache.size();
    }

    @Override
    public void close() throws IOException {
        db.close();
    }
    public void saveSnapshot(
        int height,
        Map<String, Object> state,
        Map<String, Object> utxo
    ) throws IOException {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("height", height);
        snapshot.put("state", state);
        snapshot.put("utxo", utxo);
        snapshot.put("timestamp", System.currentTimeMillis());
        put("snapshot:" + height, snapshot);

        // Critical snapshot height update; use sync write
        try {
            byte[] json = mapper.writeValueAsBytes(height);
            byte[] encrypted = encrypt(json);
            WriteOptions syncOptions = new WriteOptions().sync(true);
            db.put(bytes(secureKey("meta:lastSnapshotHeight")), encrypted, syncOptions);
            cache.put("meta:lastSnapshotHeight", height);
        } catch (Exception e) {
            throw new IOException("Failed to save lastSnapshotHeight with sync", e);
        }
    }

    // ─── Transaction Indexer ──────────────────────────────────────────────────

    /**
     * Indexes a transaction ID to its block hash and height for O(1) retrieval.
     *
     * @param txid        the transaction ID
     * @param blockHash   the hash of the block containing this transaction
     * @param blockHeight the height of the block
     * @throws IOException on storage failure
     */
    public void indexTransaction(String txid, String blockHash, int blockHeight) {
        Map<String, Object> loc = new HashMap<>();
        loc.put("blockHash", blockHash);
        loc.put("blockHeight", blockHeight);
        put("txidx:" + txid, loc);
    }

    /**
     * Indexes an address→txid mapping for address history queries.
     * Key format: {@code addridx:ADDRESS:TIMESTAMP_TXID} to preserve insertion order.
     *
     * @param address   the account address
     * @param txid      the transaction ID
     * @param timestamp the block timestamp in milliseconds
     * @throws IOException on storage failure
     */
    public void indexAddressTx(String address, String txid, long timestamp) {
        String sortKey = String.format("%016d_%s", timestamp, txid);
        put("addridx:" + address + ":" + sortKey, txid);
    }

    /**
     * Returns the block location of a transaction by its ID.
     *
     * @param txid the transaction ID
     * @return a map containing "blockHash" and "blockHeight", or null if not found
     * @throws IOException on storage failure
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTransactionLocation(String txid) {
        return get("txidx:" + txid, Map.class);
    }

    /**
     * Returns a paginated list of transaction IDs for the given address.
     *
     * @param address the account address
     * @param limit   maximum number of results to return
     * @param cursor  pagination cursor (null for first page; use the last returned txid as cursor)
     * @return list of transaction IDs in reverse-chronological order
     */
    public java.util.List<String> getAddressTransactions(String address, int limit, String cursor) {
        java.util.List<String> result = new java.util.ArrayList<>();
        String prefix = "addridx:" + address + ":";
        
        try (DBIterator it = db.iterator()) {
            if (cursor == null) {
                // Iterating backwards to get newest first. 
                // Prefix + "~" is a safe way to jump to the end of the address's entries.
                it.seek(bytes(prefix + "~"));
                if (!it.hasPrev()) {
                    it.seekToLast();
                }
            } else {
                it.seek(bytes(prefix + cursor));
                if (it.hasNext()) it.next(); // skip cursor
            }

            int count = 0;
            while (count < limit) {
                if (cursor == null) {
                    if (!it.hasPrev()) break;
                    Map.Entry<byte[], byte[]> entry = it.prev();
                    String k = asString(entry.getKey());
                    if (!k.startsWith(prefix)) break;
                    try {
                        byte[] decrypted = decrypt(entry.getValue());
                        result.add(mapper.readValue(decrypted, String.class));
                        count++;
                    } catch (Exception e) {}
                } else {
                    if (!it.hasNext()) break;
                    Map.Entry<byte[], byte[]> entry = it.next();
                    String k = asString(entry.getKey());
                    if (!k.startsWith(prefix)) break;
                    try {
                        byte[] decrypted = decrypt(entry.getValue());
                        result.add(mapper.readValue(decrypted, String.class));
                        count++;
                    } catch (Exception e) {}
                }
            }
        } catch (Exception e) {
            // Best-effort
        }
        return result;
    }

    // ─── Transaction Receipts ─────────────────────────────────────────────────

    /**
     * Saves a transaction receipt, indexed by transaction ID.
     *
     * @param txid    the transaction ID
     * @param receipt the receipt to persist
     * @throws IOException on storage failure
     */
    public void saveReceipt(String txid, TransactionReceipt receipt) {
        put("receipt:" + txid, receipt);
    }

    /** Overload for tests that pass just the Receipt. */
    public void saveReceipt(TransactionReceipt receipt) {
        saveReceipt(receipt.getTxid(), receipt);
    }

    /**
     * Loads a transaction receipt by transaction ID.
     *
     * @param txid the transaction ID
     * @return the TransactionReceipt, or null if not found
     * @throws IOException on storage failure
     */
    public TransactionReceipt loadReceipt(String txid) {
        return get("receipt:" + txid, TransactionReceipt.class);
    }

    // ─── Device Telemetry ─────────────────────────────────────────────────────

    /**
     * Saves a telemetry record for a device at the given block height.
     *
     * @param deviceId    the device identifier
     * @param blockHeight the block height of the telemetry submission
     * @param txid        the telemetry transaction ID
     * @param data        the telemetry payload (or its SHA-256 hash if too large)
     * @throws IOException on storage failure
     */
    public void saveTelemetry(String deviceId, int blockHeight, String txid, byte[] data) {
        String sortKey = String.format("%010d_%s", blockHeight, txid);
        Map<String, Object> record = new HashMap<>();
        record.put("deviceId", deviceId);
        record.put("blockHeight", blockHeight);
        record.put("txid", txid);
        record.put("data", HexUtils.encode(data));
        put("telem:" + deviceId + ":" + sortKey, record);
    }

    /** 3-arg overload without txid for tests. */
    public void saveTelemetry(String deviceId, int blockHeight, byte[] data) {
        saveTelemetry(deviceId, blockHeight, "test-txid", data);
    }

    /** Simplified telemetry loader for tests that expect raw byte array back. */
    public byte[] loadTelemetry(String deviceId, int blockHeight) {
        java.util.List<Map<String, Object>> list = getTelemetry(deviceId, blockHeight, blockHeight);
        if (list == null || list.isEmpty()) return null;
        String enc = (String) list.get(0).get("data");
        return enc != null ? HexUtils.decode(enc) : null;
    }

    /**
     * Returns the telemetry records for a device within an inclusive block height range.
     *
     * @param deviceId  the device identifier
     * @param fromBlock inclusive start block height
     * @param toBlock   inclusive end block height
     * @return list of telemetry records as maps (fields: deviceId, blockHeight, txid, data)
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> getTelemetry(String deviceId, int fromBlock, int toBlock) {
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
        String prefix = "telem:" + deviceId + ":";
        String startKey = prefix + String.format("%010d", fromBlock);
        String endKey = prefix + String.format("%010d", toBlock + 1);

        try (DBIterator it = db.iterator()) {
            it.seek(bytes(startKey));
            while (it.hasNext()) {
                Map.Entry<byte[], byte[]> entry = it.next();
                String k = asString(entry.getKey());
                if (!k.startsWith(prefix) || k.compareTo(endKey) >= 0) break;
                try {
                    byte[] decrypted = decrypt(entry.getValue());
                    Map<String, Object> record = mapper.readValue(decrypted, Map.class);
                    result.add(record);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return result;
    }

    // ─── Checkpoints ─────────────────────────────────────────────────────────

    /**
     * Saves a checkpoint for fast synchronization.
     *
     * @param cp the Checkpoint to persist
     * @throws IOException on storage failure
     */
    public void saveCheckpoint(com.hybrid.blockchain.Checkpoint cp) {
        put("checkpoint:" + cp.getBlockHeight(), cp);
    }

    /**
     * Loads the most recent checkpoint from storage.
     *
     * @return the latest Checkpoint, or null if none exists
     */
    public com.hybrid.blockchain.Checkpoint loadLatestCheckpoint() {
        String prefix = "checkpoint:";
        // Scan backwards to find the highest checkpoint key
        try (DBIterator it = db.iterator()) {
            it.seek(bytes(prefix + "~")); // past all checkpoint keys
            if (it.hasPrev()) {
                Map.Entry<byte[], byte[]> entry = it.prev();
                String k = asString(entry.getKey());
                if (k.startsWith(prefix)) {
                    try {
                        byte[] decrypted = decrypt(entry.getValue());
                        return mapper.readValue(decrypted, com.hybrid.blockchain.Checkpoint.class);
                    } catch (Exception e) { /* corrupted */ }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Loads a checkpoint at the given block height.
     *
     * @param height the block height
     * @return the Checkpoint, or null if not found
     * @throws IOException on storage failure
     */
    public com.hybrid.blockchain.Checkpoint loadCheckpointAtHeight(int height) {
        return get("checkpoint:" + height, com.hybrid.blockchain.Checkpoint.class);
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private String secureKey(String key) {
        return key;
    }

    private static String asString(byte[] bytes) {
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}

