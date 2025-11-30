package com.hybrid.blockchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class Storage {

    private final String dbPath;
    private final ObjectMapper mapper;
    private final Map<String, Object> cache;

    public Storage(String dbPath) {
        this.dbPath = dbPath != null ? dbPath : "data";
        this.mapper = new ObjectMapper();
        this.cache = new HashMap<>();
        new File(this.dbPath).mkdirs();
    }

    private File getFile(String key) {
        return Paths.get(dbPath, key + ".json").toFile();
    }

    public void put(String key, Object value) throws IOException {
        cache.put(key, value);
        mapper.writeValue(getFile(key), value);
    }

    public <T> T get(String key, Class<T> clazz) throws IOException {
        if (cache.containsKey(key)) {
            return clazz.cast(cache.get(key));
        }
        File file = getFile(key);
        if (!file.exists()) return null;
        T value = mapper.readValue(file, clazz);
        cache.put(key, value);
        return value;
    }

    public void del(String key) {
        cache.remove(key);
        File file = getFile(key);
        if (file.exists()) file.delete();
    }

    public void saveBlock(String hash, Block block) throws IOException {
        put("block:" + hash, block);
        put("height:" + block.getIndex(), hash);
        put("chain:tip", hash);
    }

    public Block loadBlockByHash(String hash) throws IOException {
        return get(hashKey("block", hash), Block.class);
    }

    public Block loadBlockByHeight(int height) throws IOException {
        String hash = get(heightKey(height), String.class);
        if (hash == null) return null;
        return loadBlockByHash(hash);
    }

    public String loadTipHash() throws IOException {
        return get("chain:tip", String.class);
    }

    // ------------------- UTXO Set -------------------
    public void saveUTXO(Map<String, Object> obj) throws IOException {
        put("utxo:set", obj);
    }

    public Map<String, Object> loadUTXO() throws IOException {
        Map<String, Object> data = get("utxo:set", Map.class);
        return data != null ? data : new HashMap<>();
    }

    // ------------------- Account State -------------------
    public void saveState(Map<String, Object> obj) throws IOException {
        put("state:account", obj);
    }

    public Map<String, Object> loadState() throws IOException {
        Map<String, Object> data = get("state:account", Map.class);
        return data != null ? data : new HashMap<>();
    }

    // ------------------- Mempool -------------------
    public void saveMempool(Object arr) throws IOException {
        put("mempool", arr);
    }

    public Object loadMempool() throws IOException {
        Object data = get("mempool", Object.class);
        return data != null ? data : new Object[0];
    }

    // ------------------- Metadata -------------------
    public void putMeta(String key, Object value) throws IOException {
        put("meta:" + key, value);
    }

    public Object getMeta(String key) throws IOException {
        return get("meta:" + key, Object.class);
    }

    private String hashKey(String prefix, String key) {
        return prefix + ":" + key;
    }

    private String heightKey(int height) {
        return "height:" + height;
    }
    
}
