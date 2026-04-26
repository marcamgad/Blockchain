package com.hybrid.blockchain;

import com.hybrid.blockchain.testutil.TestTransactionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
public class MempoolTest {

    @Test
    @DisplayName("Invariant: Mempool prioritizes by fee")
    void testMempoolPrioritization() {
        Mempool mempool = new Mempool(10);
        TestKeyPair kp = new TestKeyPair(1);
        
        // transaction 1: low fee
        Transaction tx1 = TestTransactionFactory.createAccountTransfer(kp, "to1", 100, 5, 1);
        // transaction 2: high fee
        Transaction tx2 = TestTransactionFactory.createAccountTransfer(kp, "to2", 100, 50, 2);
        
        mempool.add(tx2);
        mempool.add(tx1);
        
        List<Transaction> top = mempool.getTop(2);
        assertThat(top).hasSize(2);
        assertThat(top.get(0).getId()).isEqualTo(tx2.getId()); // tx2 has higher fee
    }

    @Test
    @DisplayName("Security: Replacement requires higher fee")
    void testReplacementPolicy() {
        Mempool mempool = new Mempool(10);
        TestKeyPair kp = new TestKeyPair(1);
        
        Transaction tx1 = TestTransactionFactory.createAccountTransfer(kp, "to1", 100, 10, 1);
        mempool.add(tx1);
        
        // Same nonce, lower fee
        Transaction txLow = TestTransactionFactory.createAccountTransfer(kp, "to1", 100, 5, 1);
        assertThatThrownBy(() -> mempool.add(txLow))
                .isInstanceOf(IllegalArgumentException.class);
        
        // Same nonce, higher fee
        Transaction txHigh = TestTransactionFactory.createAccountTransfer(kp, "to1", 100, 20, 1);
        assertThat(mempool.add(txHigh)).isTrue();
        assertThat(mempool.size()).isEqualTo(1);
        assertThat(mempool.getTop(1).get(0).getFee()).isEqualTo(20);
    }

    @Test
    @DisplayName("Security: Mempool evicts lowest fee transaction when full")
    void testEvictionPolicy() {
        Mempool mempool = new Mempool(2); // Small capacity
        TestKeyPair kp = new TestKeyPair(1);
        
        Transaction tx1 = TestTransactionFactory.createAccountTransfer(kp, "to1", 100, 1, 1);
        Transaction tx2 = TestTransactionFactory.createAccountTransfer(kp, "to2", 100, 10, 2);
        
        mempool.add(tx1);
        mempool.add(tx2);
        
        // tx3 has higher fee than tx1 but lower than tx2
        Transaction tx3 = TestTransactionFactory.createAccountTransfer(kp, "to3", 100, 5, 3);
        
        assertThat(mempool.add(tx3)).isTrue();
        assertThat(mempool.size()).isEqualTo(2);
        
        List<String> ids = mempool.toArray().stream().map(Transaction::getId).toList();
        assertThat(ids).contains(tx2.getId(), tx3.getId());
        assertThat(ids).doesNotContain(tx1.getId()); // tx1 evicted
    }

    @Test
    @DisplayName("Concurrency: Mempool allows concurrent additions safely")
    void testConcurrentAdditions() throws InterruptedException {
        Mempool mempool = new Mempool(2000);
        int threadCount = 10;
        int txsPerThread = 100;
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int tId = i;
            new Thread(() -> {
                TestKeyPair kp = new TestKeyPair(tId + 1);
                try {
                    startLatch.await();
                    for (int j = 1; j <= txsPerThread; j++) {
                        Transaction tx = TestTransactionFactory.createAccountTransfer(kp, "to" + tId, 10, 10, j);
                        mempool.add(tx);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown(); // Start all threads at once
        doneLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(mempool.size()).isEqualTo(threadCount * txsPerThread);
    }
}
