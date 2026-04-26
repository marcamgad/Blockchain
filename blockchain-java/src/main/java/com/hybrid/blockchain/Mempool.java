package com.hybrid.blockchain;

// FIX 6: Unified locking strategy — single ReentrantReadWriteLock over a TreeMap.
// Rationale: ConcurrentHashMap provides per-slot safety but compound operations
// (eviction + add, replacement check + insert) cannot be made atomic without an
// external lock. Using a single RWLock over a TreeMap gives atomic compound ops,
// deterministic iteration order, and avoids the confusion of two concurrency layers.

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Thread-safe transaction memory pool for HybridChain.
 *
 * <p>Locking contract:
 * <ul>
 *   <li><b>Write lock</b>: {@link #add}, {@link #tryAdd}, {@link #remove}, {@link #drain}, {@link #clear}</li>
 *   <li><b>Read lock</b>: {@link #getTop}, {@link #getReadyTransactions}, {@link #toArray}, {@link #size}</li>
 * </ul>
 * All compound invariants (replacement policy, eviction, nonce ordering) are
 * enforced atomically within the appropriate lock scope.
 */
public class Mempool {

    private final int maxSize;

    /** Sorted by txId for deterministic iteration; protected by {@link #rwLock}. */
    private final TreeMap<String, Transaction> map;

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock  readLock  = rwLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();

    public Mempool(int maxSize) {
        this.maxSize = maxSize > 0 ? maxSize : 1000;
        this.map = new TreeMap<>();
    }

    public Mempool() {
        this(1000);
    }

    // ── Write operations ──────────────────────────────────────────────────────

    /**
     * Adds a transaction to the mempool, enforcing all replacement and eviction
     * policies atomically.
     *
     * @param tx the transaction to add
     * @return {@code true} if the transaction was accepted
     * @throws IllegalArgumentException if the transaction is invalid, already present,
     *                                  has a stale timestamp, is a low-fee replacement,
     *                                  or the pool is full with a better transaction present
     */
    public boolean add(Transaction tx) {
        writeLock.lock();
        try {
            return addInternal(tx);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Attempts to add a transaction within the given timeout, returning {@code false}
     * if the write lock cannot be acquired in time. Useful for callers that cannot
     * block indefinitely (e.g., network ingest handlers).
     *
     * @param tx        the transaction to add
     * @param timeoutMs maximum milliseconds to wait for the write lock
     * @return {@code true} if accepted; {@code false} if the lock timed out
     * @throws IllegalArgumentException if the transaction fails validation checks
     * @throws InterruptedException     if the calling thread is interrupted while waiting
     */
    public boolean tryAdd(Transaction tx, long timeoutMs) throws InterruptedException {
        if (!writeLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
            return false;
        }
        try {
            return addInternal(tx);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Removes a transaction by ID. Protected by write lock so it cannot race
     * with concurrent {@link #add} calls.
     *
     * @param txid the transaction ID to remove
     */
    public void remove(String txid) {
        writeLock.lock();
        try {
            map.remove(txid);
        } finally {
            writeLock.unlock();
        }
    }

    public List<Transaction> drain(int n) {
        writeLock.lock();
        try {
            List<Transaction> top = topInternal(n);
            for (Transaction tx : top) {
                map.remove(tx.getId());
            }
            return top;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Removes the specified transactions from the pool.
     *
     * @param txs the transactions to remove
     */
    public void drain(Collection<Transaction> txs) {
        if (txs == null) return;
        writeLock.lock();
        try {
            for (Transaction tx : txs) {
                map.remove(tx.getId());
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Clears all pending transactions.
     */
    public void clear() {
        writeLock.lock();
        try {
            map.clear();
        } finally {
            writeLock.unlock();
        }
    }

    // ── Read operations ───────────────────────────────────────────────────────

    /**
     * Returns (without removing) the top {@code n} transactions by fee density.
     *
     * @param n maximum number of transactions to return
     * @return ordered list (highest fee-density first)
     */
    public List<Transaction> getTop(int n) {
        readLock.lock();
        try {
            return topInternal(n);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Returns up to {@code n} transactions that are sequentially ready to execute
     * given the current committed {@link AccountState} (nonce continuity enforced).
     *
     * @param n     maximum transactions to return
     * @param state the current committed account state (used for nonce lookup)
     * @return list of ready transactions, ordered by fee density
     */
    public List<Transaction> getReadyTransactions(int n, AccountState state) {
        readLock.lock();
        try {
            // Group transactions by sender and sort by nonce
            Map<String, List<Transaction>> bySender = new HashMap<>();
            List<Transaction> systemTxs = new ArrayList<>();
            
            for (Transaction tx : map.values()) {
                if (tx.getFrom() == null) {
                    systemTxs.add(tx);
                } else {
                    bySender.computeIfAbsent(tx.getFrom(), k -> new ArrayList<>()).add(tx);
                }
            }

            for (List<Transaction> txs : bySender.values()) {
                txs.sort(Comparator.comparingLong(Transaction::getNonce));
            }

            // Fee-density comparator for the priority queue
            Comparator<Transaction> feeDensityComp = Comparator.<Transaction>comparingDouble(
                    tx -> (double) tx.getFee() / Math.max(1, tx.serializeCanonical().length)).reversed();

            PriorityQueue<Transaction> candidateQueue = new PriorityQueue<>(feeDensityComp);
            Map<String, Integer> senderNextIndex = new HashMap<>();

            // Initial candidates for each sender (must be baseNonce + 1)
            for (Map.Entry<String, List<Transaction>> entry : bySender.entrySet()) {
                String sender = entry.getKey();
                List<Transaction> txs = entry.getValue();
                long baseNonce = state.getNonce(sender);
                
                for (int i = 0; i < txs.size(); i++) {
                    if (txs.get(i).getNonce() == baseNonce + 1) {
                        candidateQueue.add(txs.get(i));
                        senderNextIndex.put(sender, i + 1);
                        break;
                    }
                }
            }
            
            // All system transactions are immediately ready
            candidateQueue.addAll(systemTxs);

            List<Transaction> ready = new ArrayList<>();
            while (!candidateQueue.isEmpty() && ready.size() < n) {
                Transaction tx = candidateQueue.poll();
                ready.add(tx);

                if (tx.getFrom() != null) {
                    String sender = tx.getFrom();
                    int nextIdx = senderNextIndex.getOrDefault(sender, -1);
                    List<Transaction> txs = bySender.get(sender);
                    if (nextIdx != -1 && nextIdx < txs.size()) {
                        Transaction nextTx = txs.get(nextIdx);
                        if (nextTx.getNonce() == tx.getNonce() + 1) {
                            candidateQueue.add(nextTx);
                            senderNextIndex.put(sender, nextIdx + 1);
                        }
                    }
                }
            }
            return ready;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Returns a snapshot of all transactions currently in the pool.
     *
     * @return defensive copy of all pending transactions
     */
    public List<Transaction> toArray() {
        readLock.lock();
        try {
            return new ArrayList<>(map.values());
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Returns the current number of pending transactions.
     *
     * @return pool size
     */
    public int size() {
        readLock.lock();
        try {
            return map.size();
        } finally {
            readLock.unlock();
        }
    }

    public int getSize() {
        return size();
    }

    // ── Internal helpers (caller must hold the appropriate lock) ──────────────

    private boolean addInternal(Transaction tx) {
        if (tx == null || tx.getId() == null)
            throw new IllegalArgumentException("Invalid tx");
        long now = System.currentTimeMillis();
        if (Math.abs(now - tx.getTimestamp()) > 1000L * 60 * 60 * 24)
            throw new IllegalArgumentException("Transaction too old");
        if (map.containsKey(tx.getId()))
            throw new IllegalArgumentException("tx already in mempool");

        // Nonce replacement: evict same-sender/same-nonce tx only if new fee is higher
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Transaction> entry : map.entrySet()) {
            Transaction existing = entry.getValue();
            if (existing.getType() == Transaction.Type.ACCOUNT
                    && tx.getType() == Transaction.Type.ACCOUNT
                    && existing.getFrom() != null
                    && existing.getFrom().equals(tx.getFrom())
                    && existing.getNonce() == tx.getNonce()) {
                if (tx.getFee() <= existing.getFee())
                    throw new IllegalArgumentException("Replacement fee too low");
                toRemove.add(entry.getKey());
            }
        }
        toRemove.forEach(map::remove);

        // Eviction: remove the lowest-fee-density tx when pool is full
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

    private List<Transaction> topInternal(int n) {
        return map.values().stream()
                .sorted(Comparator.<Transaction>comparingDouble(
                        tx -> (double) tx.getFee() / Math.max(1, tx.serializeCanonical().length)).reversed())
                .limit(n)
                .collect(Collectors.toList());
    }
}
