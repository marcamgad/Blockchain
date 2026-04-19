package com.hybrid.blockchain;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [TEST-C9] Token Transfer Race Condition Tests.
 */
@Tag("Core")
@Tag("Concurrency")
public class TokenRaceConditionTest {

    private TestBlockchain tb;
    private Blockchain blockchain;

    @BeforeEach
    void setup() throws Exception {
        tb = new TestBlockchain();
        blockchain = tb.getBlockchain();
    }

    @AfterEach
    void teardown() throws Exception {
        if (tb != null) tb.close();
    }

    @Test
    @DisplayName("C9.1: Concurrent Token Transfers Preservation")
    void testTokenRaceCondition() throws Exception {
        TestKeyPair sender = new TestKeyPair(200);
        TestKeyPair recipient1 = new TestKeyPair(201);
        TestKeyPair recipient2 = new TestKeyPair(202);
        
        blockchain.getState().credit(sender.getAddress(), 1000);
        
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger(0);

        // Submit two transfers concurrently to the mempool
        executor.submit(() -> {
            try {
                latch.await();
                Transaction tx1 = new Transaction.Builder()
                        .from(sender.getAddress())
                        .to(recipient1.getAddress())
                        .amount(500)
                        .fee(0)
                        .nonce(blockchain.getState().getNonce(sender.getAddress()) + 1)
                        .build();
                tx1.sign(sender.getPrivateKey());
                blockchain.addTransaction(tx1);
            } catch (Exception e) {
                failures.incrementAndGet();
            }
        });

        executor.submit(() -> {
            try {
                latch.await();
                Transaction tx2 = new Transaction.Builder()
                        .from(sender.getAddress())
                        .to(recipient2.getAddress())
                        .amount(500)
                        .fee(0)
                        .nonce(blockchain.getState().getNonce(sender.getAddress()) + 1) // Same nonce = one must fail
                        .build();
                tx2.sign(sender.getPrivateKey());
                blockchain.addTransaction(tx2);
            } catch (Exception e) {
                failures.incrementAndGet();
            }
        });

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Only 1 transaction should be in mempool because they used same nonce
        assertThat(blockchain.getMempool().size()).isEqualTo(1);
        
        // Final balance check after mining
        BlockApplier.createAndApplyBlock(tb, blockchain.getMempool().drain(10));
        
        long totalTransferred = blockchain.getState().getBalance(recipient1.getAddress()) 
                                + blockchain.getState().getBalance(recipient2.getAddress());
        assertThat(totalTransferred).isEqualTo(500);
        assertThat(blockchain.getState().getBalance(sender.getAddress())).isEqualTo(500);
    }
}
