package com.hybrid.blockchain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A simplified Merkle Patricia Trie (MPT) implementation for cryptographic state verification.
 * Maintains an ordered state map and computes a cryptographic root hash via a Merkle Tree.
 * In a full production environment, this would natively store intermediate nodes in LevelDB.
 */
public class MerklePatriciaTrie {
    
    private final TreeMap<String, byte[]> stateMap = new TreeMap<>();
    private byte[] cachedRoot = null;
    private boolean isDirty = true;

    public void put(String key, byte[] value) {
        stateMap.put(key, value);
        isDirty = true;
    }

    public byte[] get(String key) {
        return stateMap.get(key);
    }

    public void delete(String key) {
        stateMap.remove(key);
        isDirty = true;
    }

    public byte[] getRootHash() {
        if (!isDirty && cachedRoot != null) {
            return cachedRoot;
        }
        
        if (stateMap.isEmpty()) {
            cachedRoot = new byte[32]; // 32 bytes of zeros
            isDirty = false;
            return cachedRoot;
        }

        List<byte[]> leaves = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : stateMap.entrySet()) {
            byte[] keyBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] valBytes = entry.getValue();
            
            byte[] combined = new byte[keyBytes.length + valBytes.length];
            System.arraycopy(keyBytes, 0, combined, 0, keyBytes.length);
            System.arraycopy(valBytes, 0, combined, keyBytes.length, valBytes.length);
            
            leaves.add(Crypto.hash(combined));
        }

        cachedRoot = MerkleTree.computeRoot(leaves);
        isDirty = false;
        return cachedRoot;
    }

    public Map<String, byte[]> toMap() {
        return stateMap;
    }

    public void fromMap(Map<String, byte[]> map) {
        stateMap.clear();
        stateMap.putAll(map);
        isDirty = true;
    }
}
