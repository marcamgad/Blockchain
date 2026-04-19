package com.hybrid.blockchain;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safety contract:
 * - Internal transaction storage uses a ConcurrentHashMap for safe concurrent access.
 * - Compound invariants (replacement policy/full-pool eviction) are enforced by callers
 *   using higher-level locks (e.g., Blockchain write lock) to keep decisions atomic.
 */
public class Mempool {
    private final int maxSize;
    private final Map<String, Transaction> map;

    public Mempool(int maxSize) {
        this.maxSize = maxSize > 0 ? maxSize : 1000;
        this.map = new ConcurrentHashMap<>();
    }

    public Mempool() {
        this(1000);
    }

    public synchronized boolean add(Transaction tx) {
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

    public synchronized List<Transaction> getTop(int n) {
        // Explicitly type the comparator to avoid Object issues in VSCode
        return map.values().stream()
                .sorted(Comparator.<Transaction>comparingDouble(
                        tx -> (double) tx.getFee() / Math.max(1, tx.serializeCanonical().length)).reversed())
                .limit(n)
                .collect(Collectors.toList());
    }

    public synchronized List<Transaction> drain(int n) {
        List<Transaction> top = getTop(n);
        for (Transaction tx : top) {
            map.remove(tx.getId());
        }
        return top;
    }

    public synchronized List<Transaction> getReadyTransactions(int n, AccountState state) {
        // Map to track current nonce for each sender (starts from ledger state)
        Map<String, Long> currentNonces = new HashMap<>();
        
        List<Transaction> all = new ArrayList<>(map.values());
        // Sort by fee density (fee/size) to prioritize high-value txs
        all.sort(Comparator.<Transaction>comparingDouble(
                tx -> (double) tx.getFee() / Math.max(1, tx.serializeCanonical().length)).reversed());

        List<Transaction> ready = new ArrayList<>();
        for (Transaction tx : all) {
            if (ready.size() >= n) break;
            
            if (tx.getFrom() == null) {
                // System transactions (MINT) are always ready
                ready.add(tx);
                continue;
            }
            
            long baseNonce = currentNonces.computeIfAbsent(tx.getFrom(), k -> state.getNonce(k));
            if (tx.getNonce() == baseNonce + 1) {
                ready.add(tx);
                currentNonces.put(tx.getFrom(), tx.getNonce());
            }
        }
        return ready;
    }

    public synchronized List<Transaction> toArray() {
        return new ArrayList<>(map.values());
    }

    public int size() {
        return map.size();
    }

    public void clear() {
        map.clear();
    }
}
