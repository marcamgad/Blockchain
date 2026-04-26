package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import com.hybrid.blockchain.testutil.TestTransactionFactory;

public class ForkResolutionTest {

    private Blockchain blockchain;
    private Storage storage;
    private PBFTConsensus consensus;
    private Mempool mempool;
    private TestKeyPair sender;

    @BeforeEach
    void setUp() throws Exception {
        storage = new Storage("target/test-db-" + java.util.UUID.randomUUID().toString());
        mempool = new Mempool(100);
        consensus = Mockito.mock(PBFTConsensus.class);
        
        Map<String, byte[]> validators = new HashMap<>();
        validators.put("ValidatorA", new byte[33]);
        validators.put("ValidatorB", new byte[33]);
        
        when(consensus.isValidator(any())).thenReturn(true);
        java.util.List<Validator> mockValidators = new java.util.ArrayList<>();
        mockValidators.add(new Validator("ValidatorA", new byte[33]));
        mockValidators.add(new Validator("ValidatorB", new byte[33]));
        when(consensus.getValidators()).thenReturn(mockValidators);
        when(consensus.verifyBlock(any(), any())).thenReturn(true);

        blockchain = new Blockchain(storage, mempool, consensus);
        blockchain.init();
        
        // Establish a funded state in Block 1 so it survives reorganizations
        sender = new TestKeyPair(1);
        Transaction mint = TestTransactionFactory.createMint(sender.getAddress(), 50, 0);
        Block b1 = new Block(1, System.currentTimeMillis(), java.util.Collections.singletonList(mint), blockchain.getLatestBlock().getHash(), blockchain.getDifficulty(), "");
        b1.setValidatorId("ValidatorA");
        b1.setSignature(new byte[64]);
        
        AccountState simState = blockchain.getState().cloneState();
        simState.setBlockHeight(1);
        blockchain.applyTransactionToState(simState, blockchain.getUTXOSet(), mint, 1, b1.getTimestamp(), b1.getHash(), new java.util.ArrayList<>());
        b1.setStateRoot(simState.calculateStateRoot());
        b1.setTxRoot(b1.calculateTxRoot());
        
        // Bypassing validation for test blocks
        blockchain = Mockito.spy(blockchain);
        Mockito.doNothing().when(blockchain).validateBlock(any());
        blockchain.applyBlock(b1);
        
        storage.saveSnapshot(1, blockchain.getState().toJSON(), blockchain.getUTXOSet().toJSON());
    }

    @Test
    void testDeterministicForkResolution() throws Exception {
        Block precursor = blockchain.getLatestBlock();
        AccountState baseState = blockchain.getState().cloneState();
        
        Block blockLow = new Block(2, System.currentTimeMillis(), new ArrayList<>(), precursor.getHash(), blockchain.getDifficulty(), "");
        blockLow.setValidatorId("ValidatorB");
        blockLow.setSignature(new byte[64]);
        
        // Add a transaction to blockLow so we can see it returned to mempool
        Transaction tx1 = TestTransactionFactory.createAccountTransfer(sender, "receiver", 10, 1, 1);
        blockLow.getTransactions().add(tx1);
        blockLow.setTxRoot(blockLow.calculateTxRoot());
        blockLow.setHash("0000000000000000000000000000000000000000000000000000000000000000");

        Block blockHigh = new Block(2, System.currentTimeMillis() + 1000, new ArrayList<>(), precursor.getHash(), blockchain.getDifficulty(), "");
        blockHigh.setValidatorId("ValidatorA");
        blockHigh.setSignature(new byte[64]);
        blockHigh.setHash("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");

        // 2. Apply Block Low first (transitions from precursor)
        AccountState simStateLow = baseState.cloneState();
        UTXOSet simUtxoLow = UTXOSet.fromMap(blockchain.getUTXOSet().toJSON());
        
        simStateLow.setBlockHeight(blockLow.getIndex());
        blockchain.applyTransactionToState(simStateLow, simUtxoLow, tx1, 2, blockLow.getTimestamp(), blockLow.getHash(), new ArrayList<>());
        if (tx1.getFee() > 0) {
            simStateLow.credit(blockLow.getValidatorId(), tx1.getFee());
        }
        
        blockLow.setStateRoot(simStateLow.calculateStateRoot());
        
        blockchain.applyBlock(blockLow);
        assertThat(blockchain.getLatestBlock().getHash()).isEqualTo(blockLow.getHash());

        // 3. Apply Block High (the winner due to lexical hash >)
        // High also transitions from precursor during fork resolution
        AccountState simStateHigh = baseState.cloneState();
        simStateHigh.setBlockHeight(blockHigh.getIndex());
        // Empty block High, no transactions, no fees
        blockHigh.setStateRoot(simStateHigh.calculateStateRoot());
        
        blockchain.applyBlock(blockHigh);

        // 4. Verify Reorg happened (blockHigh is latest)
        assertThat(blockchain.getLatestBlock().getHash()).isEqualTo(blockHigh.getHash());
        assertThat(blockchain.getLatestBlock().getValidatorId()).isEqualTo("ValidatorA");
        
        // 5. Verify Mempool returned transactions from Loser
        assertThat(blockchain.getMempool().size()).isEqualTo(1);
        assertThat(blockchain.getMempool().getTop(1).get(0).getId()).isEqualTo(tx1.getId());
    }
    
    @Test
    void testLowerTieBreakIgnored() throws Exception {
        Block precursor = blockchain.getLatestBlock();
        
        Block blockHigh = new Block(2, System.currentTimeMillis(), new ArrayList<>(), precursor.getHash(), blockchain.getDifficulty(), "");
        blockHigh.setValidatorId("ValidatorA");
        blockHigh.setSignature(new byte[64]);
        blockHigh.setHash("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
        
        Block blockLow = new Block(2, System.currentTimeMillis() + 1000, new ArrayList<>(), precursor.getHash(), blockchain.getDifficulty(), "");
        blockLow.setValidatorId("ValidatorB");
        blockLow.setSignature(new byte[64]);
        blockLow.setHash("0000000000000000000000000000000000000000000000000000000000000000");

        // Simulate root for index 2
        AccountState simState = blockchain.getState().cloneState();
        simState.setBlockHeight(2);
        String expectedRoot = simState.calculateStateRoot();
        blockHigh.setStateRoot(expectedRoot);
        blockLow.setStateRoot(expectedRoot);

        // Apply Winner first
        blockchain.applyBlock(blockHigh);
        assertThat(blockchain.getLatestBlock().getHash()).isEqualTo(blockHigh.getHash());

        // Apply Loser second - should be ignored
        blockchain.applyBlock(blockLow);
        assertThat(blockchain.getLatestBlock().getHash()).isEqualTo(blockHigh.getHash());
    }
}
