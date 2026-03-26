package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.testutil.TestKeyPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class DoubleSpendAttackTest {

    private Blockchain blockchain;
    private Storage storage;
    private PBFTConsensus consensus;
    private Mempool mempool;

    @BeforeEach
    void setUp() throws Exception {
        storage = new Storage("target/test-db-" + java.util.UUID.randomUUID().toString());
        mempool = new Mempool(100);
        consensus = Mockito.mock(PBFTConsensus.class);
        
        when(consensus.isValidator(any())).thenReturn(true);
        when(consensus.getValidators()).thenReturn(new ArrayList<>(Collections.singletonList(new Validator("ValidatorA", new byte[33]))));
        when(consensus.verifyBlock(any(), any())).thenReturn(true);

        blockchain = new Blockchain(storage, mempool, consensus);
        blockchain.init();
        
        com.hybrid.blockchain.AccountState stateSpy = Mockito.spy(blockchain.getState());
        java.lang.reflect.Field stateField = Blockchain.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(blockchain, stateSpy);
        Mockito.doReturn("dummy_root").when(stateSpy).calculateStateRoot();
        
        TestKeyPair sender = new TestKeyPair(1);
        // Initial balance for TestAddress
        blockchain.getState().credit(sender.getAddress(), 1000);
    }

    @Test
    void testDoubleSpendRejection() throws Exception {
        TestKeyPair sender = new TestKeyPair(1);
        String from = sender.getAddress();
        String to1 = "Recipient1";
        String to2 = "Recipient2";
        long nonce = blockchain.getState().getNonce(from) + 1;

        // 1. Create two transactions with the same nonce
        Transaction tx1 = new Transaction.Builder()
                .from(from).to(to1).amount(600).fee(10).nonce(nonce).sign(sender.getPrivateKey(), sender.getPublicKey());
        Transaction tx2 = new Transaction.Builder()
                .from(from).to(to2).amount(600).fee(10).nonce(nonce).sign(sender.getPrivateKey(), sender.getPublicKey());

        // 2. Apply Block 1 with tx1
        Block block1 = new Block(1, System.currentTimeMillis(), Collections.singletonList(tx1), 
                blockchain.getLatestBlock().getHash(), blockchain.getDifficulty(), "dummy_root");
        block1.setValidatorId("ValidatorA");
        block1.setSignature(new byte[64]);
        blockchain.applyBlock(block1);

        // 3. Try to apply Block 2 with tx2 (same nonce)
        Block block2 = new Block(2, System.currentTimeMillis(), Collections.singletonList(tx2), 
                blockchain.getLatestBlock().getHash(), blockchain.getDifficulty(), "dummy_root");
        block2.setValidatorId("ValidatorA");
        block2.setSignature(new byte[64]);

        assertThatThrownBy(() -> blockchain.applyBlock(block2))
                .hasMessageContaining("Invalid nonce")
                .hasMessageContaining("expected 2 got 1"); // Error message usually contains nonce info
    }

    @Test
    void testInsufficientBalanceRejection() throws Exception {
        TestKeyPair sender = new TestKeyPair(1);
        String from = sender.getAddress();
        String to = "Recipient";
        long nonce = blockchain.getState().getNonce(from) + 1;

        // Balance is 1000. Try to spend 1100.
        Transaction tx = new Transaction.Builder()
                .from(from).to(to).amount(1100).fee(10).nonce(nonce).sign(sender.getPrivateKey(), sender.getPublicKey());

        Block block = new Block(1, System.currentTimeMillis(), Collections.singletonList(tx), 
                blockchain.getLatestBlock().getHash(), blockchain.getDifficulty(), "dummy_root");
        block.setValidatorId("ValidatorA");
        block.setSignature(new byte[64]);

        assertThatThrownBy(() -> blockchain.applyBlock(block))
                .hasMessageContaining("Insufficient funds");
    }
}
