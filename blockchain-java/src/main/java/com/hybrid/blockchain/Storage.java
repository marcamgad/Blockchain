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

public class Storage {

    private final DB db;
    private final ObjectMapper mapper;
    private final Map<String, Object> cache;
    private final SecretKeySpec aesKey;
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
        
        // Critical tip update; use sync write
        try {
            byte[] json = mapper.writeValueAsBytes(hash);
            byte[] encrypted = encrypt(json);
            WriteOptions syncOptions = new WriteOptions().sync(true);
            db.put(bytes("chain:tip"), encrypted, syncOptions);
            cache.put("chain:tip", hash);
        } catch (Exception e) {
            throw new IOException("Failed to save chain tip with sync", e);
        }
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
        @SuppressWarnings("unchecked")
        Map<String, Object> result = get("utxo:set", Map.class);
        return result;
    }

    public void saveState(Map<String, ?> obj) throws IOException {
        put("state:account", obj);
    }

    public Map<String, Object> loadState() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = get("state:account", Map.class);
        return result;
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
    
    // Critical snapshot height update; use sync write
    try {
        byte[] json = mapper.writeValueAsBytes(height);
        byte[] encrypted = encrypt(json);
        WriteOptions syncOptions = new WriteOptions().sync(true);
        db.put(bytes("meta:lastSnapshotHeight"), encrypted, syncOptions);
        cache.put("meta:lastSnapshotHeight", height);
    } catch (Exception e) {
        throw new IOException("Failed to save lastSnapshotHeight with sync", e);
    }

    System.out.println("[SNAPSHOT] Saved at height " + height);
}


}
