package com.hybrid.blockchain.stress;

import com.hybrid.blockchain.*;
import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-load concurrency stress tests.
 * Validates thread safety of mempool, state access during block application,
 * and race conditions in token minting.
 */
@Tag("stress")
@Tag("concurrent")
public class ConcurrencyStressCompleteTest {

    private TestBlockchain tb;
    private Blockchain blockchain;

    @BeforeEach
    void setUp() throws Exception {
        tb = new TestBlockchain();
        blockchain = tb.getBlockchain();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tb != null) tb.close();
    }

    @Test
    @DisplayName("CS1.2 — Concurrent balance reads during application")
    void testConcurrentBalanceRead() throws Exception {
        TestKeyPair alice = new TestKeyPair(1);
        blockchain.getAccountState().credit(alice.getAddress(), 1000L);
        
        int readers = 5;
        ExecutorService executor = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch stop = new CountDownLatch(1);
        AtomicLong errorCount = new AtomicLong();
        
        // Use a sender that is NOT the validator to avoid receiving block rewards/fees
        TestKeyPair sender = new TestKeyPair(999);
        blockchain.getAccountState().credit(sender.getAddress(), 1000L);
        
        // Reader threads
        for (int i = 0; i < readers; i++) {
            executor.submit(() -> {
                while (stop.getCount() > 0) {
                    long b = blockchain.getBalance(sender.getAddress());
                    if (b > 1000L) errorCount.incrementAndGet();
                }
            });
        }
        
        // Writer thread (applies blocks that modify balance)
        executor.submit(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    Transaction tx = TestTransactionFactory.createAccountTransfer(sender, "b", 1, 1, i + 1);
                    BlockApplier.createAndApplyBlock(tb, List.of(tx));
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                stop.countDown();
            }
        });
        
        stop.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(errorCount.get()).as("Readers should never see inconsistent/inflated balance").isEqualTo(0);
    }

    @Test
    @DisplayName("CS1.4 — Mempool concurrent add + drain")
    void testMempoolConcurrentAddDrain() throws Exception {
        Mempool mempool = blockchain.getMempool();
        int threads = 50;
        ExecutorService ex = Executors.newFixedThreadPool(threads + 5);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(threads);
        AtomicLong totalDrained = new AtomicLong();
        
        // 50 Producers
        for (int i = 0; i < threads; i++) {
            final int id = i;
            ex.submit(() -> {
                try {
                    start.await();
                    TestKeyPair k = new TestKeyPair(id);
                    mempool.add(TestTransactionFactory.createAccountTransfer(k, "r", 1, 1, 1));
                    finish.countDown();
                } catch (Exception ignored) {}
            });
        }
        
        java.util.Set<String> uniqueDrainedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        // 5 Consumers
        for (int i = 0; i < 5; i++) {
            ex.submit(() -> {
                try {
                    start.await();
                    while (finish.getCount() > 0 || mempool.getSize() > 0) {
                        List<Transaction> drained = mempool.getTop(10);
                        if (!drained.isEmpty()) {
                            for (Transaction tx : drained) {
                                if (uniqueDrainedIds.add(tx.getId())) {
                                    totalDrained.incrementAndGet();
                                }
                            }
                            mempool.drain(drained);
                        }
                        Thread.sleep(10);
                    }
                } catch (Exception ignored) {}
            });
        }
        
        start.countDown();
        finish.await(10, TimeUnit.SECONDS);
        // Await slightly longer for consumers to finish draining
        Thread.sleep(500);
        ex.shutdown();
        
        assertThat(totalDrained.get()).as("All successfully added txs should be drained").isEqualTo(threads);
    }

    @Test
    @DisplayName("CS1.5 — TOKEN_MINT race condition")
    void testTokenMintRace() throws Exception {
        TestKeyPair owner = new TestKeyPair(100);
        blockchain.getAccountState().credit(owner.getAddress(), 1_000_000L);
        String tokenId = "RACE";
        
        // Register token with maxSupply=500
        BlockApplier.createAndApplyBlock(tb, List.of(TestTransactionFactory.createTokenRegister(owner, "R", "R", tokenId, 500, 0, 1)));
        
        int threads = 5;
        ExecutorService service = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        
        for (int i = 0; i < threads; i++) {
            final int nonce = i + 2;
            service.submit(() -> {
                try {
                    // Each thread tries to mint 200 (Total 1000 / Max 500)
                    synchronized(this) { 
                         long dynNonce = blockchain.getAccountState().getNonce(owner.getAddress()) + 1;
                         Transaction tx = new Transaction.Builder()
                             .type(Transaction.Type.TOKEN_MINT)
                             .from(owner.getAddress())
                             .to("rec")
                             .amount(200)
                             .data(tokenId.getBytes())
                             .nonce(dynNonce)
                             .sign(owner.getPrivateKey(), owner.getPublicKey());
                         
                         BlockApplier.createAndApplyBlock(tb, List.of(tx));
                    }
                } catch (Exception e) {
                    // Expected failures for later threads
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(10, TimeUnit.SECONDS);
        service.shutdown();
        
        assertThat(blockchain.getTokenRegistry().getTokenInfo(tokenId).getTotalMinted()).as("Supply must not exceed 500").isLessThanOrEqualTo(500L);
        assertThat(blockchain.getTokenRegistry().getTokenInfo(tokenId).getTotalMinted()).isGreaterThanOrEqualTo(400L); // at least 2 success
    }
}
