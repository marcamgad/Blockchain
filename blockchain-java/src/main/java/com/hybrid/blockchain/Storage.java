package com.hybrid.blockchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.iq80.leveldb.*;
import org.fusesource.leveldbjni.JniDBFactory;
import org.iq80.leveldb.impl.Iq80DBFactory;

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

    // [HC-089] One atomic write batch per block. While a batch is open on a thread, that thread's
    // writes go into it and reach the disk together (sync) at commitBatch(), or not at all.
    // Thread-local, so REST or adapter threads writing at the same time are not swept into it.
    private final ThreadLocal<WriteBatch> batch = new ThreadLocal<>();
    private final ThreadLocal<java.util.Set<String>> batchCacheKeys = new ThreadLocal<>();
    /** Read-your-writes for the batch's own thread (the LRU cache may evict a batched value). */
    private final ThreadLocal<Map<String, Object>> batchValues = new ThreadLocal<>();
    private static final Object DELETED = new Object();

    public void beginBatch() {
        ensureOpen();
        if (batch.get() != null) throw new IllegalStateException("a write batch is already open on this thread");
        batch.set(db.createWriteBatch());
        batchCacheKeys.set(new java.util.HashSet<>());
        batchValues.set(new HashMap<>());
    }

    /** Writes everything since {@link #beginBatch} atomically, with a synchronous disk write. */
    public void commitBatch() throws IOException {
        WriteBatch b = batch.get();
        if (b == null) return;
        try {
            db.write(b, new WriteOptions().sync(true));
        } finally {
            batch.remove();
            batchCacheKeys.remove();
            batchValues.remove();
            b.close();
        }
    }

    /** Drops everything since {@link #beginBatch}, including the cache entries it touched. */
    public void abortBatch() {
        WriteBatch b = batch.get();
        if (b == null) return;
        java.util.Set<String> touched = batchCacheKeys.get();
        batch.remove();
        batchCacheKeys.remove();
        batchValues.remove();
        if (touched != null) touched.forEach(cache::remove);
        try { b.close(); } catch (IOException ignored) { }
    }

    private void dbPut(String cacheKey, byte[] key, byte[] value, WriteOptions options) {
        WriteBatch b = batch.get();
        if (b != null) {
            b.put(key, value);
            batchCacheKeys.get().add(cacheKey);
        } else if (options != null) {
            db.put(key, value, options);
        } else {
            db.put(key, value);
        }
    }

    private void dbDelete(String cacheKey, byte[] key) {
        WriteBatch b = batch.get();
        if (b != null) {
            b.delete(key);
            batchCacheKeys.get().add(cacheKey);
        } else {
            db.delete(key);
        }
    }
    private final ObjectMapper mapper;
    private final Map<String, Object> cache;
    private final SecretKeySpec aesKey;
    private final String dbPath;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128; // in bits
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile boolean closed = false;

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

        DBFactory factory;
        try {
            JniDBFactory.factory.pushMemoryPool(1048576);
            JniDBFactory.factory.popMemoryPool();
            factory = JniDBFactory.factory;
        } catch (Throwable t) {
            factory = Iq80DBFactory.factory;
        }
        this.db = factory.open(folder, options);
    }

    // Fallback constructor (dev / testing)
    public Storage(String dbPath) throws IOException {
      this(dbPath, Config.STORAGE_AES_KEY);
    }
    
    public String getDbPath() {
        return this.dbPath;
    }

    /**
     * [HC-041] AES-GCM with the storage key as Additional Authenticated Data. Before, a record
     * encrypted for one key decrypted cleanly under any other key, so anyone with write access to
     * the data directory could move records around (e.g. copy an old snapshot or a different meta
     * value over the current one) without knowing the encryption key.
     */
    private byte[] encrypt(byte[] data, String storedKey) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, spec);
        cipher.updateAAD(aad(storedKey));
        byte[] ciphertext = cipher.doFinal(data);
        byte[] encrypted = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, encrypted, 0, iv.length);
        System.arraycopy(ciphertext, 0, encrypted, iv.length, ciphertext.length);
        return encrypted;
    }

    private byte[] decrypt(byte[] data, String storedKey) throws Exception {
        if (data == null || data.length < GCM_IV_LENGTH) throw new IllegalArgumentException("Invalid encrypted data");
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(data, 0, iv, 0, GCM_IV_LENGTH);
        byte[] ciphertext = new byte[data.length - GCM_IV_LENGTH];
        System.arraycopy(data, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, aesKey, spec);
        cipher.updateAAD(aad(storedKey));
        return cipher.doFinal(ciphertext);
    }

    public void put(String key, Object value) {
        ensureOpen();
        if (key == null) return;
        try {
            cache.put(key, value);
            Map<String, Object> pending = batchValues.get();
            if (pending != null) pending.put(key, value);
            byte[] json = mapper.writeValueAsBytes(value);
            byte[] encrypted = encrypt(json, secureKey(key));
            dbPut(key, bytes(secureKey(key)), encrypted, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to store encrypted value", e);
        }
    }

    public <T> T get(String key, Class<T> clazz) {
        ensureOpen();
        if (key == null) return null;
        Map<String, Object> pending = batchValues.get();
        if (pending != null && pending.containsKey(key)) {
            Object v = pending.get(key);
            return v == DELETED ? null : clazz.cast(v);
        }
        if (cache.containsKey(key)) {
            return clazz.cast(cache.get(key));
        }
        try {
            String sk = secureKey(key);
            if (sk == null) return null;
            byte[] encrypted = db.get(bytes(sk));
            if (encrypted == null) return null;
            byte[] decrypted = decrypt(encrypted, sk);
            T value = mapper.readValue(decrypted, clazz);
            cache.put(key, value);
            return value;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load encrypted value", e);
        }
    }

    public void del(String key) {
        ensureOpen();
        if (key == null) return;
        cache.remove(key);
        Map<String, Object> pending = batchValues.get();
        if (pending != null) pending.put(key, DELETED);
        String sk = secureKey(key);
        if (sk != null) {
            dbDelete(key, bytes(sk));
        }
    }

    public void saveBlock(String hash, Block block) {
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        put(blockStorageKey(hash), block);
        put("height:" + block.getIndex(), hash);

        // Critical tip update; use sync write
        try {
            byte[] json = mapper.writeValueAsBytes(hash);
            byte[] encrypted = encrypt(json, secureKey("chain:tip"));
            WriteOptions syncOptions = new WriteOptions().sync(true);
            dbPut("chain:tip", bytes(secureKey("chain:tip")), encrypted, syncOptions);
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
        Block block = get(blockStorageKey(hash), Block.class);
        if (block != null) return block;
        return get("block:" + hash, Block.class);
    }

    /**
     * [HC-031] Deletes a block body under the key it is actually stored under (plus the legacy
     * "block:"+hash key). PrunedBlockchain used to delete only "block:"+hash, which never matched
     * blockStorageKey(hash), so pruning freed nothing.
     */
    public void deleteBlock(String hash) {
        if (hash == null) return;
        del(blockStorageKey(hash));
        del("block:" + hash);
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
        Object value = get("meta:" + key, Object.class);
        if (value == null) return null;

        if (isNumericMetaKey(key)) {
            return value;
        }

        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }

        return value;
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
        if (closed) return;
        closed = true;
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
            byte[] encrypted = encrypt(json, secureKey("meta:lastSnapshotHeight"));
            WriteOptions syncOptions = new WriteOptions().sync(true);
            dbPut("meta:lastSnapshotHeight", bytes(secureKey("meta:lastSnapshotHeight")), encrypted, syncOptions);
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
                        byte[] decrypted = decrypt(entry.getValue(), k);
                        result.add(mapper.readValue(decrypted, String.class));
                        count++;
                    } catch (Exception e) {}
                } else {
                    if (!it.hasNext()) break;
                    Map.Entry<byte[], byte[]> entry = it.next();
                    String k = asString(entry.getKey());
                    if (!k.startsWith(prefix)) break;
                    try {
                        byte[] decrypted = decrypt(entry.getValue(), k);
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
                    byte[] decrypted = decrypt(entry.getValue(), k);
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
        putMeta("lastCheckpointHeight", cp.getBlockHeight());
    }

    /**
     * Loads the most recent checkpoint from storage.
     *
     * @return the latest Checkpoint, or null if none exists
     */
    public com.hybrid.blockchain.Checkpoint loadLatestCheckpoint() {
        Object latestHeight = getMeta("lastCheckpointHeight");
        if (latestHeight instanceof Number) {
            com.hybrid.blockchain.Checkpoint cp = loadCheckpointAtHeight(((Number) latestHeight).intValue());
            if (cp != null) return cp;
        } else if (latestHeight instanceof String) {
            try {
                com.hybrid.blockchain.Checkpoint cp = loadCheckpointAtHeight(Integer.parseInt((String) latestHeight));
                if (cp != null) return cp;
            } catch (NumberFormatException ignored) {}
        }

        String prefix = "checkpoint:";
        // Scan backwards to find the highest checkpoint key
        try (DBIterator it = db.iterator()) {
            it.seek(bytes(prefix + "~")); // past all checkpoint keys
            if (it.hasPrev()) {
                Map.Entry<byte[], byte[]> entry = it.prev();
                String k = asString(entry.getKey());
                if (k.startsWith(prefix)) {
                    try {
                        byte[] decrypted = decrypt(entry.getValue(), k);
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

    /** [HC-041] AAD = domain tag + the exact key bytes the record is stored under. */
    private static byte[] aad(String storedKey) {
        return ("HC-STORAGE-v2\u0000" + storedKey).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String secureKey(String key) {
        return key;
    }

    private static String asString(byte[] bytes) {
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value == null ? null : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean isNumericMetaKey(String key) {
        if (key == null) return false;
        return key.equals("difficulty")
                || key.equals("totalMinted")
                || key.equals("baseFee")
                || key.equals("lastSnapshotHeight")
                || key.startsWith("rep:");
    }

    private static String blockStorageKey(String hash) {
        if (hash == null) return null;
        return "block:" + Crypto.bytesToHex(Crypto.hash(("block:" + hash).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Storage is closed");
        }
    }
}
