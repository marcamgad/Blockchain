package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.testutil.TestKeyPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class SSTORECorrectionTest {

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
    }

    @Test
    void testSSTOREOrder() throws Exception {
        // SSTORE opcode is 0x55
        // EVM stack: [..., KEY, VALUE] (top)
        // Correct pop order: pop VALUE, then pop KEY.
        // Then putStorage(addr, KEY, VALUE).
        
        // PUSH1 0xAA (VALUE)
        // PUSH1 0x11 (KEY)
        // SSTORE
        // Bytecode: 60 AA 60 11 55
        java.nio.ByteBuffer codeBuffer = java.nio.ByteBuffer.allocate(25);
        codeBuffer.put(com.hybrid.blockchain.OpCode.PUSH.getByte()).putLong(170L); // VALUE (0xAA)
        codeBuffer.put(com.hybrid.blockchain.OpCode.PUSH.getByte()).putLong(17L);  // KEY (0x11)
        codeBuffer.put(com.hybrid.blockchain.OpCode.SSTORE.getByte());
        byte[] bytecode = java.util.Arrays.copyOf(codeBuffer.array(), codeBuffer.position());
        
        TestKeyPair sender = new TestKeyPair(1);
        String fromAddress = sender.getAddress();

        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.CONTRACT)
                .from(fromAddress)
                .to("ContractAddress")
                .data(bytecode)
                .nonce(1)
                .fee(50000)
                .sign(sender.getPrivateKey(), sender.getPublicKey());
        
        // Setup contract account
        blockchain.getState().ensure("ContractAddress");
        blockchain.getState().setCode("ContractAddress", bytecode);
        blockchain.getState().credit(fromAddress, 100000);
        
        Block block = new Block(1, System.currentTimeMillis(), Collections.singletonList(tx), 
                blockchain.getLatestBlock().getHash(), blockchain.getDifficulty(), "dummy_root");
        block.setValidatorId("ValidatorA");
        block.setSignature(new byte[64]);
        
        // Apply block
        blockchain.applyBlock(block);
        
        // Verify storage: Key 0x11 should have value 0xAA
        long key = 0x11;
        long expectedValue = 0xAA;
        long actualValue = blockchain.getState().getAccountStorage("ContractAddress").getStorage().getOrDefault(key, 0L);
        
        assertThat(actualValue).isEqualTo(expectedValue);
    }
}
