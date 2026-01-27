package com.hybrid.blockchain;

import java.util.*;
import java.util.stream.Collectors;

public class Mempool {
    private final int maxSize;
    private final Map<String, Transaction> map;

    public Mempool(int maxSize) {
        this.maxSize = maxSize > 0 ? maxSize : 1000;
        this.map = new HashMap<>(); // type inferred, safe
    }

    public Mempool() {
        this(1000);
    }

    public boolean add(Transaction tx) {
        if (tx == null || tx.getId() == null)
            throw new IllegalArgumentException("Invalid tx");
        long now = System.currentTimeMillis();
        if (Math.abs(now - tx.getTimestamp()) > 1000L * 60 * 60 * 24)
            throw new IllegalArgumentException("tx timestamp out of range");
        if (map.containsKey(tx.getId()))
            throw new IllegalArgumentException("tx already in mempool");

        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Transaction> entry : map.entrySet()) {
            Transaction existing = entry.getValue();
            if (existing.getType() == Transaction.Type.ACCOUNT && tx.getType() == Transaction.Type.ACCOUNT
                    && existing.getFrom() != null && existing.getFrom().equals(tx.getFrom())
                    && existing.getNonce() == tx.getNonce()) {
                if (tx.getFee() <= existing.getFee())
                    throw new IllegalArgumentException("replacement must have higher fee");
                toRemove.add(entry.getKey());
            }
        }
        toRemove.forEach(map::remove);

        if (map.size() >= maxSize) {
            String worstId = null;
            double worstFeePerByte = Double.MAX_VALUE;
            for (Map.Entry<String, Transaction> entry : map.entrySet()) {
                Transaction etx = entry.getValue();
                double feePerByte = (double) etx.getFee() / Math.max(1, etx.serializeCanonical().length);
                if (feePerByte < worstFeePerByte) {
                    worstFeePerByte = feePerByte;
                    worstId = entry.getKey();
                }
            }
            double newFeePerByte = (double) tx.getFee() / Math.max(1, tx.serializeCanonical().length);
            if (newFeePerByte <= worstFeePerByte)
                throw new IllegalArgumentException("mempool full and fee too low");
            if (worstId != null)
                map.remove(worstId);
        }

        map.put(tx.getId(), tx);
        return true;
    }

    public void remove(String txid) {
        map.remove(txid);
    }

    public List<Transaction> getTop(int n) {
        // Explicitly type the comparator to avoid Object issues in VSCode
        return map.values().stream()
                .sorted(Comparator.<Transaction>comparingDouble(
                        tx -> (double) tx.getFee() / Math.max(1, tx.serializeCanonical().length)).reversed())
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
