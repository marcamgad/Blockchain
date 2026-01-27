package com.hybrid.blockchain;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Block class.
 */
public class SimpleBlockTest {

    private String genesisHash = "0000000000000000000000000000000000000000000000000000000000000000";
    private String dummyStateRoot = "0000000000000000000000000000000000000000000000000000000000000000";

    @Test
    public void testBlockCreation() {
        Block block = new Block(0, System.currentTimeMillis(), new ArrayList<>(), genesisHash, 1, dummyStateRoot);

        assertNotNull(block);
        assertEquals(0, block.getIndex());
        assertEquals(genesisHash, block.getPrevHash());
    }

    @Test
    public void testBlockWithTransactions() {
        List<Transaction> txs = new ArrayList<>();
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .from("alice")
                .to("bob")
                .amount(100)
                .fee(1)
                .build();
        txs.add(tx);

        Block block = new Block(1, System.currentTimeMillis(), txs, genesisHash, 1, dummyStateRoot);
        assertEquals(1, block.getTransactions().size());
    }

    @Test
    public void testBlockHashGeneration() {
        Block block1 = new Block(0, 12345, new ArrayList<>(), genesisHash, 1, dummyStateRoot);
        Block block2 = new Block(0, 12345, new ArrayList<>(), genesisHash, 1, dummyStateRoot);

        // Same inputs should produce same hash
        assertEquals(block1.getHash(), block2.getHash());
    }

    @Test
    public void testBlockChaining() {
        Block block1 = new Block(0, System.currentTimeMillis(), new ArrayList<>(), genesisHash, 1, dummyStateRoot);
        Block block2 = new Block(1, System.currentTimeMillis(), new ArrayList<>(), block1.getHash(), 1, dummyStateRoot);

        assertEquals(block1.getHash(), block2.getPrevHash());
    }

    @Test
    public void testBlockIndexSequence() {
        Block block0 = new Block(0, System.currentTimeMillis(), new ArrayList<>(), genesisHash, 1, dummyStateRoot);
        Block block1 = new Block(1, System.currentTimeMillis(), new ArrayList<>(), block0.getHash(), 1, dummyStateRoot);
        Block block2 = new Block(2, System.currentTimeMillis(), new ArrayList<>(), block1.getHash(), 1, dummyStateRoot);

        assertEquals(0, block0.getIndex());
        assertEquals(1, block1.getIndex());
        assertEquals(2, block2.getIndex());
    }

    @Test
    public void testBlockEmptyTransactions() {
        Block block = new Block(0, System.currentTimeMillis(), new ArrayList<>(), genesisHash, 1, dummyStateRoot);
        assertEquals(0, block.getTransactions().size());
    }

    @Test
    public void testBlockMultipleTransactions() {
        List<Transaction> txs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Transaction tx = new Transaction.Builder()
                    .type(Transaction.Type.ACCOUNT)
                    .to("receiver" + i)
                    .amount(100 + i)
                    .fee(1)
                    .build();
            txs.add(tx);
        }

        Block block = new Block(1, System.currentTimeMillis(), txs, genesisHash, 1, dummyStateRoot);
        assertEquals(5, block.getTransactions().size());
    }

    @Test
    public void testBlockPublicFields() {
        Block block = new Block(5, 999, new ArrayList<>(), "prev_hash", 2, dummyStateRoot);

        assertEquals(5, block.index);
        assertEquals(999, block.timestamp);
        assertEquals("prev_hash", block.prevHash);
        assertEquals(2, block.difficulty);
        assertNotNull(block.hash);
    }

    @Test
    public void testBlockNonceIncrement() throws Exception {
        Block block = new Block(0, System.currentTimeMillis(), new ArrayList<>(), genesisHash, 1, dummyStateRoot);

        // Mine with low difficulty and high nonce limit
        block.mine(1, 10000);

        // Should have incremented nonce
        assertTrue(block.getNonce() > 0);
        // Hash should start with at least one zero
        assertTrue(block.getHash().startsWith("0"));
    }

    @Test
    public void testBlockDifficulty() {
        Block easy = new Block(0, System.currentTimeMillis(), new ArrayList<>(), genesisHash, 1, dummyStateRoot);
        Block hard = new Block(0, System.currentTimeMillis(), new ArrayList<>(), genesisHash, 3, dummyStateRoot);

        assertEquals(1, easy.getDifficulty());
        assertEquals(3, hard.getDifficulty());
    }

    @Test
    public void testBlockTimestamp() {
        long now = System.currentTimeMillis();
        Block block = new Block(0, now, new ArrayList<>(), genesisHash, 1, dummyStateRoot);

        assertEquals(now, block.getTimestamp());
    }
}
