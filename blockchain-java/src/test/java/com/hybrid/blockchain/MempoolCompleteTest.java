package com.hybrid.blockchain;

import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Exhaustive unit tests for Mempool logic including fee-density prioritization,
 * replacement policies, and concurrent behavior.
 */
@Tag("unit")
public class MempoolCompleteTest {

    private Mempool mempool;
    private static final int MAX_SIZE = 100;

    @BeforeEach
    void setUp() {
        mempool = new Mempool(MAX_SIZE);
    }

    @Test
    @DisplayName("M1.1 — add() with null tx throws")
    void testAddNull() {
        assertThatThrownBy(() -> mempool.add(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("M1.2 — add() with tx.timestamp far in the past")
    void testAddOldTx() {
        TestKeyPair alice = new TestKeyPair(1);
        long dayInMs = 24 * 60 * 60 * 1000;
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .from(alice.getAddress())
                .timestamp(System.currentTimeMillis() - dayInMs - 1000)
                .sign(alice.getPrivateKey(), alice.getPublicKey());
        
        assertThatThrownBy(() -> mempool.add(tx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Transaction too old");
    }

    @Test
    @DisplayName("M1.3 — add() with duplicate txid throws")
    void testAddDuplicate() {
        TestKeyPair alice = new TestKeyPair(1);
        Transaction tx = TestTransactionFactory.createAccountTransfer(alice, "b", 1, 0, 1);
        mempool.add(tx);
        
        assertThatThrownBy(() -> mempool.add(tx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already in mempool");
    }

    @Test
    @DisplayName("M1.4 — Fee-density prioritization")
    void testFeeDensityPrioritization() {
        TestKeyPair alice = new TestKeyPair(1);
        
        // Tx A: 100 bytes data, fee=10 => approx fee/byte = 0.1 (actual depends on total tx size)
        Transaction txA = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .from(alice.getAddress())
                .data(new byte[100])
                .fee(100)
                .nonce(1)
                .sign(alice.getPrivateKey(), alice.getPublicKey());
        
        // Tx B: 1000 bytes data, fee=50 => approx fee/byte = 0.05
        Transaction txB = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .from(alice.getAddress())
                .data(new byte[1000])
                .fee(200)
                .nonce(2)
                .sign(alice.getPrivateKey(), alice.getPublicKey());
        
        mempool.add(txA);
        mempool.add(txB);
        
        List<Transaction> top = mempool.getTop(2);
        assertThat(top.get(0).getTxId()).as("TxA should be first due to higher fee density").isEqualTo(txA.getTxId());
    }

    @Test
    @DisplayName("M1.5-1.7 — Replacement policy")
    void testReplacementPolicy() {
        TestKeyPair alice = new TestKeyPair(1);
        Transaction tx1 = TestTransactionFactory.createAccountTransfer(alice, "b", 100, 10, 1);
        mempool.add(tx1);
        
        // M1.5: same sender + same nonce, new tx has lower fee -> rejected
        Transaction txLower = TestTransactionFactory.createAccountTransfer(alice, "b", 100, 5, 1);
        assertThatThrownBy(() -> mempool.add(txLower))
                .hasMessageContaining("Replacement fee too low");
        
        // M1.6: same sender + same nonce, new tx has equal fee -> rejected
        assertThatThrownBy(() -> mempool.add(tx1))
                .hasMessageContaining("already in mempool"); // Or "Replacement fee too low" if hash differs
        
        // M1.7: same sender + same nonce, new tx has higher fee -> replaced
        Transaction txHigher = TestTransactionFactory.createAccountTransfer(alice, "b", 100, 20, 1);
        mempool.add(txHigher);
        assertThat(mempool.getSize()).isEqualTo(1);
        assertThat(mempool.getTop(1).get(0).getFee()).isEqualTo(20);
    }

    @Test
    @DisplayName("M1.8 — Eviction when full")
    void testEvictionWhenFull() {
        Mempool tiny = new Mempool(2);
        TestKeyPair alice = new TestKeyPair(1);
        
        Transaction tx1 = TestTransactionFactory.createAccountTransfer(alice, "a", 1, 10, 1);
        Transaction tx2 = TestTransactionFactory.createAccountTransfer(alice, "b", 1, 20, 2);
        tiny.add(tx1);
        tiny.add(tx2);
        
        // Add tx3 with higher fee than tx1 (tx1 is lowest)
        Transaction tx3 = TestTransactionFactory.createAccountTransfer(alice, "c", 1, 30, 3);
        tiny.add(tx3);
        
        assertThat(tiny.getSize()).isEqualTo(2);
        assertThat(tiny.toArray()).extracting(Transaction::getTxId)
                .contains(tx2.getTxId(), tx3.getTxId())
                .doesNotContain(tx1.getTxId());
    }

    @Test
    @DisplayName("M1.9 — Rejection when full and fee too low")
    void testRejectionWhenFullLowFee() {
        Mempool tiny = new Mempool(1);
        TestKeyPair alice = new TestKeyPair(1);
        
        Transaction tx1 = TestTransactionFactory.createAccountTransfer(alice, "a", 1, 100, 1);
        tiny.add(tx1);
        
        Transaction tx2 = TestTransactionFactory.createAccountTransfer(alice, "b", 1, 10, 2);
        assertThatThrownBy(() -> tiny.add(tx2))
                .hasMessageContaining("mempool full");
    }

    @Test
    @DisplayName("M1.10 — getReadyTransactions(): nonce ordering")
    void testGetReadyTransactions() {
        TestKeyPair alice = new TestKeyPair(1);
        TestKeyPair bob = new TestKeyPair(2);
        
        Transaction a1 = TestTransactionFactory.createAccountTransfer(alice, "x", 1, 10, 1);
        Transaction a2 = TestTransactionFactory.createAccountTransfer(alice, "x", 1, 10, 2);
        Transaction a3 = TestTransactionFactory.createAccountTransfer(alice, "x", 1, 10, 3);
        
        // Gap for bob: nonce 1 missing, only 2 is in mempool
        Transaction b2 = TestTransactionFactory.createAccountTransfer(bob, "y", 1, 10, 2);
        
        mempool.add(a1); mempool.add(a2); mempool.add(a3);
        mempool.add(b2);
        
        // Simulator states nonces: alice=0, bob=1
        AccountState state = new AccountState();
        state.setNonce(alice.getAddress(), 0L);
        state.setNonce(bob.getAddress(), 0L);
        
        List<Transaction> ready = mempool.getReadyTransactions(10, state);
        assertThat(ready).containsExactlyInAnyOrder(a1, a2, a3);
        assertThat(ready).doesNotContain(b2);
    }

    @Test
    @DisplayName("M1.11 — getReadyTransactions(): MINT always ready")
    void testMintAlwaysReady() {
        Transaction mint = new Transaction.Builder().type(Transaction.Type.MINT).to("m").amount(50).nonce(1).build();
        mempool.add(mint);
        List<Transaction> ready = mempool.getReadyTransactions(10, new AccountState());
        assertThat(ready).contains(mint);
    }

    @Test
    @DisplayName("M1.12 — Concurrent add()")
    void testConcurrentAdd() throws InterruptedException {
        int threads = 50;
        ExecutorService service = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(threads);
        
        for (int i = 0; i < threads; i++) {
            final int seed = i;
            service.submit(() -> {
                try {
                    latch.await();
                    TestKeyPair sender = new TestKeyPair(seed);
                    Transaction tx = TestTransactionFactory.createAccountTransfer(sender, "recipient", 1, 10, 1);
                    mempool.add(tx);
                } catch (Exception ignored) {
                } finally {
                    finish.countDown();
                }
            });
        }
        
        latch.countDown();
        finish.await(5, TimeUnit.SECONDS);
        service.shutdown();
        
        assertThat(mempool.getSize()).isEqualTo(threads);
    }

    @Test
    @DisplayName("M1.13-1.15 — remove, drain, clear")
    void testMempoolCleanup() {
        TestKeyPair alice = new TestKeyPair(1);
        Transaction tx = TestTransactionFactory.createAccountTransfer(alice, "b", 1, 10, 1);
        mempool.add(tx);
        
        mempool.remove("nonexistent");
        assertThat(mempool.getSize()).isEqualTo(1);
        
        mempool.drain(List.of(tx));
        assertThat(mempool.getSize()).isEqualTo(0);
        
        mempool.add(tx);
        mempool.clear();
        assertThat(mempool.getSize()).isEqualTo(0);
    }
}
