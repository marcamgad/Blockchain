package com.hybrid.blockchain;

import java.util.HashMap;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Isolated persistent storage for a specific smart contract.
 * Uses a Key-Value model where keys and values are 64-bit longs.
 */
public class ContractState {
    private final Map<Long, Long> storage;

    public ContractState() {
        this.storage = new HashMap<>();
    }

    public void put(long key, long value) {
        storage.put(key, value);
    }

    public long get(long key) {
        return storage.getOrDefault(key, 0L);
    }

    public byte[] serializeCanonical() {
        // Sort keys for determinism
        java.util.List<Long> keys = new java.util.ArrayList<>(storage.keySet());
        java.util.Collections.sort(keys);

        ByteBuffer buf = ByteBuffer.allocate(keys.size() * 16 + 4);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putInt(keys.size());

        for (long key : keys) {
            buf.putLong(key);
            buf.putLong(storage.get(key));
        }

        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    public String calculateRoot() {
        return Crypto.bytesToHex(Crypto.hash(serializeCanonical()));
    }
}
