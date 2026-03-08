package com.hybrid.blockchain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class TokenomicsTest {
    private Blockchain blockchain;
    private Storage storage;
    private Consensus consensus;
    private Mempool mempool;

    private static class MockConsensus implements Consensus {
        private final List<Validator> validators = new ArrayList<>();
        private final java.util.Set<String> slashed = new java.util.HashSet<>();
        public MockConsensus() {
            validators.add(new Validator("V1", new byte[0]));
        }
        @Override public boolean validateBlock(Block block, List<Block> chain) { return true; }
        @Override public Block selectLeader(List<String> nodes, long round) { return null; }
        @Override public boolean isValidator(String id) { return true; }
        @Override public boolean verifyBlock(Block b, Validator v) { return true; }
        @Override public List<Validator> getValidators() { return validators; }
        @Override public java.util.Set<String> getSlashedValidators() { return slashed; }
        @Override public void clearSlashedValidator(String id) { slashed.remove(id); }
        public void simulateSlash(String id) { slashed.add(id); }
    }

    @BeforeEach
    public void setUp() throws Exception {
        storage = new Storage("/tmp/tokenomics_test_" + System.currentTimeMillis());
        consensus = new MockConsensus();
        mempool = new Mempool();
        blockchain = new Blockchain(storage, mempool, consensus);
        blockchain.init();
    }

    @Test
    public void testMintTransaction() throws Exception {
        Transaction mintTx = new Transaction.Builder()
                .type(Transaction.Type.MINT)
                .to("alice")
                .amount(1000)
                .build();

        List<Transaction> txs = new ArrayList<>();
        txs.add(mintTx);

        Block b1 = new Block(1, System.currentTimeMillis(), txs, blockchain.getLatestBlock().getHash(), 1, blockchain.getState().calculateStateRoot());
        b1.setValidatorId("V1");
        
        blockchain.applyBlock(b1);

        assertEquals(1000, blockchain.getBalance("alice"));
    }

    @Test
    public void testBurnTransaction() throws Exception {
        blockchain.getState().credit("alice", 1000);
        blockchain.getState().setNonce("alice", 0);

        Transaction burnTx = new Transaction.Builder()
                .type(Transaction.Type.BURN)
                .from("alice")
                .amount(400)
                .fee(10)
                .nonce(1)
                .build();

        List<Transaction> txs = new ArrayList<>();
        txs.add(burnTx);

        Block b1 = new Block(1, System.currentTimeMillis(), txs, blockchain.getLatestBlock().getHash(), 1, blockchain.getState().calculateStateRoot());
        b1.setValidatorId("V1");
        
        blockchain.applyBlock(b1);

        assertEquals(590, blockchain.getBalance("alice")); // 1000 - 400 - 10
        assertEquals(10, blockchain.getBalance("V1"));
    }

    @Test
    public void testValidatorFeeReward() throws Exception {
        blockchain.getState().credit("alice", 1000);
        blockchain.getState().setNonce("alice", 0);
        
        Transaction tx1 = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .from("alice")
                .to("bob")
                .amount(100)
                .fee(20)
                .nonce(1)
                .build();

        List<Transaction> txs = new ArrayList<>();
        txs.add(tx1);

        Block b1 = new Block(1, System.currentTimeMillis(), txs, blockchain.getLatestBlock().getHash(), 1, blockchain.getState().calculateStateRoot());
        b1.setValidatorId("V1");
        
        blockchain.applyBlock(b1);

        assertEquals(880, blockchain.getBalance("alice")); 
        assertEquals(100, blockchain.getBalance("bob"));
        assertEquals(20, blockchain.getBalance("V1"));
    }

    @Test
    public void testSlashValidator() throws Exception {
        blockchain.getState().credit("V1", 2000);
        
        // Simulate Byzantine behavior detected by consensus
        ((MockConsensus)consensus).simulateSlash("V1");

        // Apply any block to trigger slashing check
        Block b1 = new Block(1, System.currentTimeMillis(), new ArrayList<>(), blockchain.getLatestBlock().getHash(), 1, blockchain.getState().calculateStateRoot());
        b1.setValidatorId("V1");
        
        blockchain.applyBlock(b1);

        // Balance should be 2000 - 1000 penalty = 1000
        assertEquals(1000, blockchain.getBalance("V1"));
        // Slashed list should be cleared
        assertTrue(consensus.getSlashedValidators().isEmpty());
    }
}
