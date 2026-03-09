package com.hybrid.blockchain;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Isolated persistent storage for a specific smart contract.
 * Uses a Key-Value model where keys and values are 64-bit longs.
 * The MerklePatriciaTrie ensures cryptographic provability of this storage.
 */
public class ContractState {
    private final Map<Long, Long> storage;
    private final MerklePatriciaTrie storageMpt;

    public ContractState() {
        this.storage = new HashMap<>();
        this.storageMpt = new MerklePatriciaTrie();
    }

    public Map<Long, Long> getStorage() {
        return storage;
    }

    public void putAll(Map<Long, Long> data) {
        for (Map.Entry<Long, Long> e : data.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    public void put(long key, long value) {
        storage.put(key, value);
        byte[] kb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(key).array();
        byte[] vb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array();
        storageMpt.putRawKey(MerklePatriciaTrie.toNibbles(Crypto.hash(kb)), vb); 
    }

    public long get(long key) {
        return storage.getOrDefault(key, 0L);
    }

    public long getOrDefault(long key, long defaultValue) {
        return storage.getOrDefault(key, defaultValue);
    }

    public String calculateRoot() {
        return Crypto.bytesToHex(storageMpt.getRootHash());
    }

    public List<byte[]> getStorageProof(long key) {
        byte[] kb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(key).array();
        return storageMpt.getAccountProof(Crypto.hash(kb));
    }

    public static boolean verifyStorageProof(long key, long expectedValue, List<byte[]> proof, byte[] stateRoot) {
        byte[] kb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(key).array();
        byte[] vb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(expectedValue).array();
        return MerklePatriciaTrie.verifyAccountProof(Crypto.hash(kb), vb, proof, stateRoot);
    }
}
