package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class MempoolTest {

    private Transaction tx(long nonce, long fee, long timestamp) {
        BigInteger priv = BigInteger.valueOf(5000 + nonce);
        return new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .to("hb-r")
                .amount(1)
                .fee(fee)
                .nonce(nonce)
                .data(new byte[0])
                .networkId(Config.NETWORK_ID)
                .sign(priv, Crypto.derivePublicKey(priv));
    }

    @Test
    @DisplayName("Adding valid tx increments mempool size")
    void addValidTxIncrementsSize() {
        Mempool mempool = new Mempool(10);
        assertTrue(mempool.add(tx(1, 1, System.currentTimeMillis())), "Adding valid transaction must succeed");
        assertEquals(1, mempool.size(), "Mempool size must increment after successful add");
    }

    @Test
    @DisplayName("Adding null transaction throws IllegalArgumentException")
    void addNullThrows() {
        Mempool mempool = new Mempool(10);
        assertThrows(IllegalArgumentException.class, () -> mempool.add(null), "Adding null transaction must throw IllegalArgumentException");
    }

    @Test
    @DisplayName("Future timestamp beyond 24h is rejected")
    void addFutureTimestampRejected() {
        Mempool mempool = new Mempool(10);
        BigInteger priv = BigInteger.valueOf(111);
        byte[] pub = Crypto.derivePublicKey(priv);
        Transaction tx = new Transaction(
            Transaction.Type.ACCOUNT,
            Crypto.deriveAddress(pub),
            "hb-r",
            1,
            1,
            1,
            System.currentTimeMillis() + TimeUnit.HOURS.toMillis(25),
            Config.NETWORK_ID,
            new byte[0],
            0,
            List.of(),
            List.of(),
            pub,
            Crypto.sign(Crypto.hash("future".getBytes()), priv));

        assertThrows(IllegalArgumentException.class, () -> mempool.add(tx), "Transactions more than 24h in future must be rejected");
    }

    @Test
    @DisplayName("Past timestamp beyond 24h is rejected")
    void addPastTimestampRejected() {
        Mempool mempool = new Mempool(10);
        BigInteger priv = BigInteger.valueOf(112);
        byte[] pub = Crypto.derivePublicKey(priv);
        Transaction tx = new Transaction(
            Transaction.Type.ACCOUNT,
            Crypto.deriveAddress(pub),
            "hb-r",
            1,
            1,
            1,
            System.currentTimeMillis() - TimeUnit.HOURS.toMillis(25),
            Config.NETWORK_ID,
            new byte[0],
            0,
            List.of(),
            List.of(),
            pub,
            Crypto.sign(Crypto.hash("past".getBytes()), priv));

        assertThrows(IllegalArgumentException.class, () -> mempool.add(tx), "Transactions older than 24h must be rejected");
    }

    @Test
    @DisplayName("Adding duplicate transaction throws already in mempool")
    void duplicateRejected() {
        Mempool mempool = new Mempool(10);
        Transaction tx = tx(1, 1, System.currentTimeMillis());
        mempool.add(tx);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> mempool.add(tx), "Duplicate transaction IDs must be rejected");
        assertTrue(ex.getMessage().toLowerCase().contains("already"), "Duplicate rejection message must mention already present transaction");
    }

    @Test
    @DisplayName("Higher-fee replacement for same sender nonce replaces old transaction")
    void higherFeeReplacement() {
        Mempool mempool = new Mempool(10);
        BigInteger priv = BigInteger.valueOf(9991);
        byte[] pub = Crypto.derivePublicKey(priv);
        Transaction lowFee = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-r").amount(1).fee(1).nonce(1).sign(priv, pub);
        Transaction highFee = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-r").amount(1).fee(5).nonce(1).sign(priv, pub);

        mempool.add(lowFee);
        mempool.add(highFee);

        assertEquals(1, mempool.size(), "Replacement should keep mempool size constant for same sender+nonce");
        assertEquals(highFee.getId(), mempool.toArray().get(0).getId(), "Higher-fee replacement transaction must remain in mempool");
    }

    @Test
    @DisplayName("Equal-fee replacement is rejected")
    void equalFeeReplacementRejected() {
        Mempool mempool = new Mempool(10);
        BigInteger priv = BigInteger.valueOf(9992);
        byte[] pub = Crypto.derivePublicKey(priv);
        Transaction t1 = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-r").amount(1).fee(2).nonce(1).sign(priv, pub);
        Transaction t2 = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hb-r").amount(1).fee(2).nonce(1).sign(priv, pub);
        mempool.add(t1);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> mempool.add(t2), "Equal-fee replacement for same sender+nonce must be rejected");
        assertTrue(ex.getMessage().contains("higher fee") || ex.getMessage().contains("already"), "Equal-fee replacement must be rejected either by replacement-fee rule or duplicate transaction-id detection");
    }

    @Test
    @DisplayName("Mempool full rejects lower fee-per-byte transaction")
    void fullMempoolRejectsLowFee() {
        Mempool mempool = new Mempool(1);
        mempool.add(tx(1, 10, System.currentTimeMillis()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> mempool.add(tx(2, 1, System.currentTimeMillis())), "Full mempool must reject lower fee-per-byte candidate");
        assertTrue(ex.getMessage().contains("mempool full"), "Error message must indicate mempool-full low-fee rejection");
    }

    @Test
    @DisplayName("Mempool full accepts higher fee-per-byte and evicts worst")
    void fullMempoolAcceptsHigherFee() {
        Mempool mempool = new Mempool(1);
        Transaction low = tx(1, 1, System.currentTimeMillis());
        Transaction high = tx(2, 20, System.currentTimeMillis());
        mempool.add(low);
        mempool.add(high);

        assertEquals(1, mempool.size(), "Mempool size must remain capped at max size after eviction");
        assertEquals(high.getId(), mempool.toArray().get(0).getId(), "Higher fee-per-byte transaction must evict the worst candidate");
    }

    @Test
    @DisplayName("getTop returns tx sorted by fee-per-byte descending")
    void getTopSortedByFeePerByte() {
        Mempool mempool = new Mempool(10);
        Transaction t1 = tx(1, 1, System.currentTimeMillis());
        Transaction t2 = tx(2, 5, System.currentTimeMillis());
        Transaction t3 = tx(3, 3, System.currentTimeMillis());
        mempool.add(t1);
        mempool.add(t2);
        mempool.add(t3);

        List<Transaction> top = mempool.getTop(3);
        assertEquals(t2.getId(), top.get(0).getId(), "Highest fee-per-byte transaction must be first in getTop result");
    }

    @Test
    @DisplayName("getTop with n greater than size returns all tx")
    void getTopWithLargeN() {
        Mempool mempool = new Mempool(10);
        mempool.add(tx(1, 1, System.currentTimeMillis()));

        assertEquals(1, mempool.getTop(10).size(), "getTop(n>size) must return all transactions without error");
    }

    @Test
    @DisplayName("remove existing tx decrements size")
    void removeExistingDecrementsSize() {
        Mempool mempool = new Mempool(10);
        Transaction tx = tx(1, 1, System.currentTimeMillis());
        mempool.add(tx);

        mempool.remove(tx.getId());
        assertEquals(0, mempool.size(), "Removing existing txid must reduce mempool size by exactly one");
    }

    @Test
    @DisplayName("remove non-existent tx is a no-op")
    void removeNonExistentNoop() {
        Mempool mempool = new Mempool(10);
        mempool.remove("missing");
        assertEquals(0, mempool.size(), "Removing a non-existent txid must not throw and must not change size");
    }

    @Test
    @DisplayName("Concurrent additions do not throw ConcurrentModificationException and respect max size")
    void concurrentAddsRespectLimits() throws Exception {
        int threads = 20;
        int perThread = 50;
        int maxSize = 2000;
        Mempool mempool = new Mempool(maxSize);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < perThread; i++) {
                        BigInteger priv = BigInteger.valueOf(10_000L + (threadIdx * 1000L) + i);
                        Transaction tx = new Transaction.Builder()
                                .type(Transaction.Type.ACCOUNT)
                                .to("hb-r")
                                .amount(1)
                                .fee(1)
                                .nonce(i + 1)
                                .sign(priv, Crypto.derivePublicKey(priv));
                        try {
                            mempool.add(tx);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertNull(failure.get(), "Concurrent mempool inserts must not throw runtime concurrency exceptions");
        assertTrue(mempool.size() <= maxSize, "Concurrent inserts must respect configured mempool maximum size");
    }
}
