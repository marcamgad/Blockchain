package com.hybrid.blockchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.iq80.leveldb.*;
import static org.fusesource.leveldbjni.JniDBFactory.*;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Storage {

    private final DB db;
    private final ObjectMapper mapper;
    private final Map<String, Object> cache;
    private final SecretKeySpec aesKey;

    public Storage(String dbPath, byte[] aesKeyBytes) throws IOException {
        if (aesKeyBytes.length != 16 && aesKeyBytes.length != 24 && aesKeyBytes.length != 32) {
            throw new IllegalArgumentException("AES key must be 16, 24, or 32 bytes");
        }

        this.mapper = new ObjectMapper();
        this.cache = new HashMap<>();
        this.aesKey = new SecretKeySpec(aesKeyBytes, "AES");

        File folder = new File(dbPath != null ? dbPath : "data");
        folder.mkdirs();

        Options options = new Options();
        options.createIfMissing(true);
        this.db = factory.open(folder, options);
    }

    // Fallback constructor (dev / testing)
    public Storage(String dbPath) throws IOException {
      this(dbPath, Config.STORAGE_AES_KEY);
    }

    private byte[] encrypt(byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey);
        return cipher.doFinal(data);
    }

    private byte[] decrypt(byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, aesKey);
        return cipher.doFinal(data);
    }

    public void put(String key, Object value) throws IOException {
        try {
            cache.put(key, value);
            byte[] json = mapper.writeValueAsBytes(value);
            byte[] encrypted = encrypt(json);
            db.put(bytes(key), encrypted);
        } catch (Exception e) {
            throw new IOException("Failed to store encrypted value", e);
        }
    }

    public <T> T get(String key, Class<T> clazz) throws IOException {
        if (cache.containsKey(key)) {
            return clazz.cast(cache.get(key));
        }
        try {
            byte[] encrypted = db.get(bytes(key));
            if (encrypted == null) return null;
            byte[] decrypted = decrypt(encrypted);
            T value = mapper.readValue(decrypted, clazz);
            cache.put(key, value);
            return value;
        } catch (Exception e) {
            throw new IOException("Failed to load encrypted value", e);
        }
    }

    public void del(String key) {
        cache.remove(key);
        db.delete(bytes(key));
    }

    public void saveBlock(String hash, Block block) throws IOException {
        put("block:" + hash, block);
        put("height:" + block.getIndex(), hash);
        put("chain:tip", hash);
    }

    public Block loadBlockByHash(String hash) throws IOException {
        return get("block:" + hash, Block.class);
    }

    public Block loadBlockByHeight(int height) throws IOException {
        String hash = get("height:" + height, String.class);
        return hash != null ? loadBlockByHash(hash) : null;
    }

    public String loadTipHash() throws IOException {
        return get("chain:tip", String.class);
    }

    public void saveUTXO(Map<String, ?> obj) throws IOException {
        put("utxo:set", obj);
    }

    public Map<String, Object> loadUTXO() throws IOException {
        return get("utxo:set", Map.class);
    }

    public void saveState(Map<String, ?> obj) throws IOException {
        put("state:account", obj);
    }

    public Map<String, Object> loadState() throws IOException {
        return get("state:account", Map.class);
    }

    public void putMeta(String key, Object value) throws IOException {
        put("meta:" + key, value);
    }

    public Object getMeta(String key) throws IOException {
        return get("meta:" + key, Object.class);
    }

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
    putMeta("lastSnapshotHeight", height);

    System.out.println("[SNAPSHOT] Saved at height " + height);
}


}
