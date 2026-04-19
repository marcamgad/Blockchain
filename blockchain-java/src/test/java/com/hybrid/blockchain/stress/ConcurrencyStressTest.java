package com.hybrid.blockchain.stress;

import com.hybrid.blockchain.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/**
 * D1 – High-Concurrency Stress Test.
 *
 * Strategy: submit all transactions concurrently via multiple threads,
 * then mine a single block after all threads finish. This separates
 * "concurrent mempool admission" (the stress part) from "sequential
 * block application" (inherently single-threaded by design).
 */
@Tag("Stress")
public class ConcurrencyStressTest {

    private TestBlockchain tb;
    private Blockchain blockchain;

    @BeforeEach
    public void setup() throws Exception {
        tb = new TestBlockchain();
        blockchain = tb.getBlockchain();
        blockchain.setSkipRateLimit(true);
    }

    @AfterEach
    public void teardown() throws Exception {
        if (tb != null) tb.close();
    }

    @Test
    @DisplayName("D1: Concurrent mempool admission + sequential block application")
    public void testManyConcurrentTransactions() throws Exception {
        final int threadCount  = 10;
        final int txPerThread  = 20;
        final long initialBalance = 1_000_000L;

        // ── Phase 1: submit all transactions concurrently ───────────────────
        // Each thread gets its own key-pair so nonce sequences are independent.
        List<TestKeyPair> senders = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            TestKeyPair kp = new TestKeyPair(50000 + i * 1000);
            blockchain.getAccountState().credit(kp.getAddress(), initialBalance);
            senders.add(kp);
        }

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger submitted = new AtomicInteger(0);
        AtomicInteger rejected  = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int th = i;
            final TestKeyPair kp = senders.get(i);
            pool.submit(() -> {
                try {
                    ready.await();
                    for (int j = 0; j < txPerThread; j++) {
                        Transaction tx = new Transaction.Builder()
                                .from(kp.getAddress())
                                .to("sink-" + th + "-" + j)
                                .amount(10L)
                                .fee(1L)
                                .nonce(j + 1L)
                                .build();
                        tx.sign(kp.getPrivateKey());
                        try {
                            blockchain.addTransaction(tx);
                            submitted.incrementAndGet();
                        } catch (Exception e) {
                            rejected.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        ready.countDown();                           // release all threads simultaneously
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                .as("All submitter threads must finish within 30 s")
                .isTrue();

        System.out.printf("Submitted=%d  Rejected=%d  MempoolSize=%d%n",
                submitted.get(), rejected.get(), blockchain.getMempool().size());

        // ── Phase 2: mine blocks until mempool is empty ─────────────────────
        int iterations = 0;
        while (blockchain.getMempool().size() > 0 && iterations++ < 20) {
            List<Transaction> batch = blockchain.getMempool().drain(200);
            if (!batch.isEmpty()) {
                BlockApplier.createAndApplyBlock(tb, batch);
            }
        }

        // ── Assertions ───────────────────────────────────────────────────────
        // At least some transactions were admitted concurrently
        assertThat(submitted.get()).isGreaterThan(0);
        // Chain advanced
        assertThat(blockchain.getHeight()).isGreaterThanOrEqualTo(1);
    }
}
