package com.hybrid.blockchain;

import java.util.*;
import java.util.stream.Collectors;

public class Mempool {

    private final int maxSize;
    private final Map<String, Transaction> map;

    public Mempool(int maxSize) {
        this.maxSize = maxSize > 0 ? maxSize : 1000; // default max size
        this.map = new HashMap<>();
    }

    public Mempool() {
        this(1000); // default max size
    }

    public boolean add(Transaction tx) {
        if (tx == null || tx.getId() == null) throw new IllegalArgumentException("Invalid tx");

        long now = System.currentTimeMillis();
        if (Math.abs(now - tx.getTimestamp()) > 1000L * 60 * 60 * 24)
            throw new IllegalArgumentException("tx timestamp out of range");

        if (map.containsKey(tx.getId())) throw new IllegalArgumentException("tx already in mempool");

        // Replace lower-fee tx if same account and nonce
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Transaction> entry : map.entrySet()) {
            Transaction existing = entry.getValue();
            if ("account".equals(existing.getType()) && "account".equals(tx.getType())
                    && existing.getFrom() != null && existing.getFrom().equals(tx.getFrom())
                    && existing.getNonce() == tx.getNonce()) {
                if (tx.getFee() <= existing.getFee())
                    throw new IllegalArgumentException("replacement must have higher fee");
                toRemove.add(entry.getKey());
            }
        }
        toRemove.forEach(map::remove);

        // Evict lowest fee if mempool is full
        if (map.size() >= maxSize) {
            String worstId = null;
            long worstFee = Long.MAX_VALUE;
            for (Map.Entry<String, Transaction> entry : map.entrySet()) {
                long fee = entry.getValue().getFee();
                if (fee < worstFee) {
                    worstFee = fee;
                    worstId = entry.getKey();
                }
            }
            if ((tx.getFee()) <= worstFee) throw new IllegalArgumentException("mempool full and fee too low");
            if (worstId != null) map.remove(worstId);
        }

        map.put(tx.getId(), tx);
        return true;
    }

    public void remove(String txid) {
        map.remove(txid);
    }

    public List<Transaction> getTop(int n) {
        return map.values().stream()
                .sorted(Comparator.comparingLong(Transaction::getFee).reversed())
                .limit(n)
                .collect(Collectors.toList());
    }

    public List<Transaction> toArray() {
        return new ArrayList<>(map.values());
    }

    public int size() {
        return map.size();
    }

}
