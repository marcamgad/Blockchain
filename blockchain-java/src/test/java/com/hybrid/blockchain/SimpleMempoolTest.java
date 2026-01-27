package com.hybrid.blockchain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Mempool class.
 */
public class SimpleMempoolTest {

    @Test
    public void testMempoolCreation() {
        Mempool mempool = new Mempool(100);
        assertNotNull(mempool);
        assertEquals(0, mempool.size());
    }

    @Test
    public void testMempoolAddTransaction() {
        Mempool mempool = new Mempool(100);
        
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .to("bob")
                .amount(100)
                .fee(1)
                .nonce(1)
                .networkId(Config.NETWORK_ID)
                .build();
        
        assertTrue(mempool.add(tx));
        assertEquals(1, mempool.size());
    }

    @Test
    public void testMempoolCannotAddNull() {
        Mempool mempool = new Mempool(100);
        assertThrows(IllegalArgumentException.class, () -> mempool.add(null));
    }

    @Test
    public void testMempoolMultipleTransactions() {
        Mempool mempool = new Mempool(100);
        
        for (int i = 0; i < 5; i++) {
            Transaction tx = new Transaction.Builder()
                    .type(Transaction.Type.ACCOUNT)
                    .to("bob" + i)
                    .amount(100)
                    .fee(i + 1)
                    .nonce(i)
                    .networkId(Config.NETWORK_ID)
                    .build();
            assertTrue(mempool.add(tx));
        }
        
        assertEquals(5, mempool.size());
    }

    @Test
    public void testMempoolGetTop() {
        Mempool mempool = new Mempool(100);
        
        for (int i = 0; i < 5; i++) {
            Transaction tx = new Transaction.Builder()
                    .type(Transaction.Type.ACCOUNT)
                    .to("receiver" + i)
                    .amount(100)
                    .fee(i + 1)
                    .nonce(i)
                    .networkId(Config.NETWORK_ID)
                    .build();
            mempool.add(tx);
        }
        
        List<Transaction> top = mempool.getTop(3);
        assertEquals(3, top.size());
    }

    @Test
    public void testMempoolGetTopMoreThanAvailable() {
        Mempool mempool = new Mempool(100);
        
        for (int i = 0; i < 3; i++) {
            Transaction tx = new Transaction.Builder()
                    .type(Transaction.Type.ACCOUNT)
                    .to("receiver" + i)
                    .amount(100)
                    .fee(1)
                    .nonce(i)
                    .networkId(Config.NETWORK_ID)
                    .build();
            mempool.add(tx);
        }
        
        List<Transaction> top = mempool.getTop(10);
        assertEquals(3, top.size());
    }

    @Test
    public void testMempoolRemove() {
        Mempool mempool = new Mempool(100);
        
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .to("bob")
                .amount(100)
                .fee(1)
                .nonce(1)
                .networkId(Config.NETWORK_ID)
                .build();
        
        assertTrue(mempool.add(tx));
        assertEquals(1, mempool.size());
        
        mempool.remove(tx.getId());
        assertEquals(0, mempool.size());
    }

    @Test
    public void testMempoolRemoveNonexistent() {
        Mempool mempool = new Mempool(100);
        mempool.remove("nonexistent_id");
        // Should not throw
        assertEquals(0, mempool.size());
    }

    @Test
    public void testMempoolToArray() {
        Mempool mempool = new Mempool(100);
        
        for (int i = 0; i < 3; i++) {
            Transaction tx = new Transaction.Builder()
                    .type(Transaction.Type.ACCOUNT)
                    .to("receiver" + i)
                    .amount(100)
                    .fee(1)
                    .nonce(i)
                    .networkId(Config.NETWORK_ID)
                    .build();
            mempool.add(tx);
        }
        
        var arr = mempool.toArray();
        assertEquals(3, arr.size());
    }

    @Test
    public void testMempoolDefault() {
        Mempool mempool = new Mempool();
        assertNotNull(mempool);
        assertEquals(0, mempool.size());
    }

    @Test
    public void testMempoolNegativeSize() {
        // Should default to 1000
        Mempool mempool = new Mempool(-1);
        assertNotNull(mempool);
    }

    @Test
    public void testMempoolMaxSize() throws Exception {
        Mempool small = new Mempool(2);
        
        Transaction tx1 = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .to("bob1")
                .amount(100)
                .fee(1)
                .nonce(1)
                .networkId(Config.NETWORK_ID)
                .build();
        
        Transaction tx2 = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .to("bob2")
                .amount(100)
                .fee(2)
                .nonce(2)
                .networkId(Config.NETWORK_ID)
                .build();
        
        assertTrue(small.add(tx1));
        assertTrue(small.add(tx2));
        assertEquals(2, small.size());
    }

    @Test
    public void testMempoolFeeOrdering() {
        Mempool mempool = new Mempool(100);
        
        // Add transactions with different fees
        for (int i = 1; i <= 3; i++) {
            Transaction tx = new Transaction.Builder()
                    .type(Transaction.Type.ACCOUNT)
                    .to("receiver")
                    .amount(100)
                    .fee(i * 10)
                    .nonce(i)
                    .networkId(Config.NETWORK_ID)
                    .build();
            mempool.add(tx);
        }
        
        List<Transaction> top = mempool.getTop(1);
        assertEquals(1, top.size());
        // Highest fee transaction should be first
        assertTrue(top.get(0).getFee() >= 10);
    }
}
