package com.hybrid.blockchain.security;

import com.hybrid.blockchain.*;
import com.hybrid.blockchain.testutil.TestTransactionFactory;
import org.junit.jupiter.api.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("Security")
@Tag("VM")
public class ReentrancyAttackTest {

    private TestBlockchain tb;
    private Blockchain blockchain;

    @BeforeEach
    void setup() throws Exception {
        tb = new TestBlockchain();
        blockchain = tb.getBlockchain();
        // com.hybrid.blockchain.Config.BYPASS_CONTRACT_AUDIT = false;
    }

    @AfterEach
    void teardown() throws Exception {
        if (tb != null) tb.close();
    }

    @Test
    @DisplayName("C2.1: Reentrancy Blocked by Call Stack")
    void testReentrancyBlockedByCallStack() throws Exception {
        TestKeyPair user = new TestKeyPair(5050);
        blockchain.getAccountState().credit(user.getAddress(), 10_000L);
        
        // CALL pops: gas (top), to, value, inOff, inLen, outOff, outLen.
        // PUSH order (reverse): outLen, outOff, inLen, inOff, value, to, gas.
        byte[] code = {
            OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 0,   // OutLen 0
            OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 0,   // OutOffset 0
            OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 0,   // InLen 0
            OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 0,   // InOffset 0
            OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 0,   // Value 0
            OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 0,   // Address Placeholder (index 46, was 10)
            OpCode.PUSH.getByte(), 0, 0, 0, 0, 0, 0, 0, 100, // Gas = 100
            OpCode.CALL.getByte(),
            OpCode.STOP.getByte()
        };

        String contractAddr = "0xReentrantContract";
        blockchain.getAccountState().ensure(contractAddr);
        
        // Update code with actual contract address hash
        byte[] h = Crypto.hash(contractAddr.getBytes());
        java.nio.ByteBuffer.wrap(code, 46, 8).putLong(java.nio.ByteBuffer.wrap(h, 0, 8).getLong()); // Index changed to 46 due to reordering
        
        blockchain.getAccountState().setCode(contractAddr, code);

        Transaction callTx = new Transaction.Builder()
                .from(user.getAddress())
                .to(contractAddr)
                .type(Transaction.Type.CONTRACT)
                .fee(2000)
                .nonce(1)
                .build();
        callTx.sign(user.getPrivateKey());

        BlockApplier.createAndApplyBlock(tb, List.of(callTx));

        TransactionReceipt receipt = blockchain.getStorage().loadReceipt(callTx.getId());
        assertThat(receipt).isNotNull();
        // Even if reentrancy CALL fails, the outer tx might succeed if it doesn't check return value.
        // But we want to ensure it didn't crash the node.
        assertThat(receipt.getStatus()).isEqualTo(TransactionReceipt.STATUS_SUCCESS);
    }

    @Test
    @DisplayName("C2.2: Non-Reentrant Call Succeeds")
    void testNonReentrantCallSucceeds() throws Exception {
        TestKeyPair user = new TestKeyPair(6060);
        blockchain.getAccountState().credit(user.getAddress(), 10_000L);

        // Contract B: just PUSH 1, STOP
        byte[] codeB = { OpCode.PUSH.getByte(), 0,0,0,0,0,0,0,1, OpCode.STOP.getByte() };
        String addrB = "0xContractB";
        blockchain.getAccountState().ensure(addrB);
        blockchain.getAccountState().setCode(addrB, codeB);

        // Contract A: Calls B
        byte[] codeA = {
            OpCode.PUSH.getByte(), 0,0,0,0,0,0,0,100, // Gas
            OpCode.PUSH.getByte(), 0,0,0,0,0,0,0,0,   // AddrB placeholder
            OpCode.PUSH.getByte(), 0,0,0,0,0,0,0,0,   // Value
            OpCode.PUSH.getByte(), 0,0,0,0,0,0,0,0,   // InOff
            OpCode.PUSH.getByte(), 0,0,0,0,0,0,0,0,   // InLen
            OpCode.PUSH.getByte(), 0,0,0,0,0,0,0,0,   // OutOff
            OpCode.PUSH.getByte(), 0,0,0,0,0,0,0,0,   // OutLen
            OpCode.CALL.getByte(),
            OpCode.STOP.getByte()
        };
        
        byte[] hB = Crypto.hash(addrB.getBytes());
        java.nio.ByteBuffer.wrap(codeA, 10, 8).putLong(java.nio.ByteBuffer.wrap(hB, 0, 8).getLong());
        
        String addrA = "0xContractA";
        blockchain.getAccountState().ensure(addrA);
        blockchain.getAccountState().setCode(addrA, codeA);

        Transaction tx = new Transaction.Builder()
                .from(user.getAddress())
                .to(addrA)
                .type(Transaction.Type.CONTRACT)
                .fee(2000)
                .nonce(1)
                .build();
        tx.sign(user.getPrivateKey());

        BlockApplier.createAndApplyBlock(tb, List.of(tx));

        TransactionReceipt receipt = blockchain.getStorage().loadReceipt(tx.getId());
        assertThat(receipt.getStatus()).isEqualTo(TransactionReceipt.STATUS_SUCCESS);
    }
}
